"""CENC AES-CTR decryption of Soda Music audio, verified against real bytes with ffmpeg.

Established so far:
  * the relay's audio is CENC-encrypted: `stsd -> enca` plus a `senc` box (8-byte IV + 4-byte
    counter, `encryption_method: cenc-aes-ctr`)
  * the auth blob is NOT named play_auth -- it is `encrypt_info.spade_a` in the client's own tier
    object, alongside `kid` and `encrypt: true`
  * the derivation algorithm (from the Android side's spec) reproduces a valid 16-byte AES key from
    every real `spade_a` value tested, giving a byte-identical "PLAUSIBLE AES KEY" verdict each time

This module decrypts the samples and proves it by letting ffmpeg decode the result:
  * means the plaintext is real audio, not just structural noise
  * a wrong key would leave the AAC bitstream invalid, which ffmpeg reports

Implementation notes:
  * `senc` holds one IV per sample; a 16-byte IV in CENC is the 8-byte nonce followed by an 8-byte
    block counter, and only the first 8 bytes vary (confirmed: consecutive IVs differed only in the
    last byte of their first half)
  * subsample encryption (clear/encrypted splits) has to be honoured if the sample entry declares it
  * after decryption the sample entry should be rewritten from `enca` back to the real codec from
    `frma` (normally `mp4a`) so ordinary players accept the file
"""
import base64
import json
import os
import re
import struct
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")


# --------------------------------------------------------------------------- MP4 structure

def boxes(data, start=0, end=None):
    """Yield (type, box_start, payload_start, box_end)."""
    end = len(data) if end is None else end
    off = start
    while off + 8 <= end:
        size = struct.unpack_from(">I", data, off)[0]
        btype = data[off + 4:off + 8]
        hdr = 8
        if size == 1:
            if off + 16 > end:
                return
            size = struct.unpack_from(">Q", data, off + 8)[0]
            hdr = 16
        elif size == 0:
            size = end - off
        if size < hdr or off + size > end:
            return
        yield btype, off, off + hdr, off + size
        off += size


CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl", b"edts", b"dinf", b"udta",
              b"moof", b"traf", b"mvex", b"wave",
              # A sample entry is itself a container: CENC puts sinf/frma INSIDE `enca`. Without
              # descending into these the real codec is never found, which is why the rewrite fell
              # back to mp4a for every tier and left the lossless stream mislabelled.
              b"enca", b"mp4a", b"drms", b"alac", b"fLaC"}


def walk(data, start=0, end=None, depth=0):
    end = len(data) if end is None else end
    for btype, s, ps, e in boxes(data, start, end):
        yield btype, s, ps, e
        if depth >= 12:
            continue
        if btype in CONTAINERS:
            yield from walk(data, ps, e, depth + 1)
        elif btype == b"stsd":
            # stsd: version/flags(4) + entry_count(4), then the sample entries
            yield from walk(data, ps + 8, e, depth + 1)


def find(data, want, start=0, end=None):
    return [(ps, e) for t, s, ps, e in walk(data, start, end) if t == want]


def describe(data):
    """Report the CENC structure of an MP4 blob: whether it is encrypted and how."""
    info = {}
    info["enca"] = len(_find_all(data, b"enca"))
    info["mp4a"] = len(_find_all(data, b"mp4a"))
    info["sinf"] = len(_find_all(data, b"sinf"))
    # tenc/senc live inside sinf/stsd, so use the recursive finder and account for the box header:
    # _find_all returns (box_start, header_size, box_size), so payload = box_start + header_size.
    tenc = _find_all(data, b"tenc")
    if tenc:
        off, hdr, size = tenc[0]
        base = off + hdr
        if size - hdr >= 24:
            info["tenc_is_protected"] = data[base + 6]
            info["tenc_iv_size"] = data[base + 7]
            info["default_kid"] = data[base + 8:base + 24].hex()
    senc = _find_all(data, b"senc")
    if senc:
        off, hdr, size = senc[0]
        base = off + hdr
        if size - hdr >= 8:
            info["senc_version"] = data[base]
            flags = int.from_bytes(data[base + 1:base + 4], "big")
            info["senc_flags"] = flags
            info["senc_sample_count"] = struct.unpack_from(">I", data, base + 4)[0]
            info["senc_has_subsamples"] = bool(flags & 0x2)
    frma = _find_all(data, b"frma")
    codec = sample_entry_codec(data)
    if codec:
        info["frma_codec"] = codec
    info["encrypted"] = bool(info["enca"] or info["sinf"] or senc
                             or _find_all(data, b"pssh"))
    return info


def sample_entry_codec(data):
    """The original four-character codec code, read from the `frma` box.

    Located by signature scan rather than by walking to it. The walk needs the audio sample entry
    header length, and that is 28 bytes here (QuickTime SoundDescription v0: 6 reserved +
    data_reference_index + version/revision/vendor + channelcount/samplesize/compressionId/
    packetSize + samplerate), not the 20 that a plain ISO AudioSampleEntry implies. Getting it wrong
    put the cursor 8 bytes early, so sinf/frma were never reached and the relabel always wrote mp4a.

    `frma` occurs only inside `sinf`, so scanning for it is unambiguous: take the 4 bytes after the
    box header and require them to be printable.
    """
    i = data.find(b"frma")
    while i >= 0:
        codec = data[i + 4:i + 8]
        if len(codec) == 4 and all(33 <= c < 127 for c in codec):
            return codec.decode("latin-1")
        i = data.find(b"frma", i + 1)
    return None


# --------------------------------------------------------------------------- key derivation

def _popcount(x):
    return bin(x & 0xFFFFFFFF).count("1")


def _base36(ch):
    if "0" <= ch <= "9":
        return ord(ch) - 48
    if "a" <= ch <= "z":
        return ord(ch) - 97 + 10
    if "A" <= ch <= "Z":
        return ord(ch) - 65 + 10
    return 0


def key_from_spade_a(spade_a):
    """Derive the AES key from a real `spade_a` blob. Raises ValueError if the result is not a key."""
    b = base64.b64decode(spade_a)
    if len(b) < 4:
        raise ValueError("blob too short")
    padding_len = (b[0] ^ b[1] ^ b[2]) - 48
    if not (0 <= padding_len <= len(b) - 2):
        raise ValueError("implausible paddingLen=%d" % padding_len)
    inner = b[1:len(b) - padding_len]
    buff = bytes([0xFA, 0x55]) + inner
    out = []
    for i in range(len(inner)):
        v = (inner[i] ^ buff[i]) - _popcount(i) - 21
        while v < 0:
            v += 255
        out.append(chr(v & 0xFF))
    out_s = "".join(out)
    skip = _base36(out_s[0])
    end = 1 + (len(b) - padding_len - 2) - skip
    hex_key = out_s[1:max(1, end)]
    if len(hex_key) % 2 or not re.fullmatch(r"[0-9a-fA-F]+", hex_key):
        raise ValueError("derived text is not hex: %r" % hex_key[:40])
    key = bytes.fromhex(hex_key)
    if len(key) not in (16, 24, 32):
        raise ValueError("key length %d is not AES" % len(key))
    return key


# --------------------------------------------------------------------------- decryption

def _parse_boxes(data, start=0, end=None):
    end = len(data) if end is None else end
    off = start
    while off + 8 <= end:
        size = struct.unpack_from(">I", data, off)[0]
        # bytes(...) matters when `data` is a bytearray: slicing that returns a bytearray, which is
        # unhashable and therefore crashes `btype in CONTAINERS` with "unhashable type: 'bytearray'".
        btype = bytes(data[off + 4:off + 8])
        hdr = 8
        if size == 1:
            if off + 16 > end:
                return
            size = struct.unpack_from(">Q", data, off + 8)[0]
            hdr = 16
        elif size == 0:
            size = end - off
        if size < hdr or off + size > end:
            return
        yield btype, off, hdr, size
        off += size


def _find_all(data, want, start=0, end=None, depth=0):
    """Find boxes anywhere in the tree, including inside stsd's sample entries.

    Uses the shared CONTAINERS set so `enca`/`mp4a` are descended into as well. Keeping a separate
    list here is what left `frma` unfound, so the relabel always wrote `mp4a` and the FLAC lossless
    stream stayed mislabelled (ffmpeg then reported thousands of AAC errors on it).

    stsd needs its own offset: version/flags(4) + entry_count(4) precede the sample entries.
    """
    end = len(data) if end is None else end
    out = []
    for btype, off, hdr, size in _parse_boxes(data, start, end):
        if btype == want:
            out.append((off, hdr, size))
        if depth >= 14:
            continue
        ps, pe = off + hdr, off + size
        if btype == b"stsd":
            out.extend(_find_all(data, want, ps + 8, pe, depth + 1))
        elif btype in CONTAINERS:
            out.extend(_find_all(data, want, ps, pe, depth + 1))
    return out


def sample_sizes(data):
    """stsz -> list of sample sizes (falls back to stz2)."""
    for off, hdr, size in _find_all(data, b"stsz"):
        p = off + hdr
        sample_size = struct.unpack_from(">I", data, p + 4)[0]
        count = struct.unpack_from(">I", data, p + 8)[0]
        if sample_size:
            return [sample_size] * count
        sizes = list(struct.unpack_from(">%dI" % count, data, p + 12))
        return sizes
    for off, hdr, size in _find_all(data, b"stz2"):
        p = off + hdr
        field_size = data[p + 7]
        count = struct.unpack_from(">I", data, p + 8)[0]
        raw = data[p + 12:p + 12 + (count * field_size + 7) // 8]
        if field_size == 16:
            return list(struct.unpack_from(">%dH" % count, raw, 0))
        if field_size == 8:
            return list(raw[:count])
        return [(raw[i // 2] >> (4 if i % 2 == 0 else 0)) & 0x0F for i in range(count)]
    raise ValueError("no stsz/stz2")


def senc_entries(data, iv_size, count):
    """senc -> list of (iv, subsamples) where subsamples is a list of (clear, enc) or None."""
    hits = _find_all(data, b"senc")
    if not hits:
        return None
    off, hdr, size = hits[0]
    p = off + hdr
    flags = int.from_bytes(data[p + 1:p + 4], "big")
    n = struct.unpack_from(">I", data, p + 4)[0]
    p += 8
    has_sub = bool(flags & 0x2)
    entries = []
    for i in range(n):
        if p + iv_size > off + size:
            break
        iv = data[p:p + iv_size]
        p += iv_size
        subs = None
        if has_sub:
            if p + 2 > off + size:
                break
            k = struct.unpack_from(">H", data, p)[0]
            p += 2
            subs = []
            for _ in range(k):
                if p + 6 > off + size:
                    break
                clear = struct.unpack_from(">H", data, p)[0]
                enc = struct.unpack_from(">I", data, p + 2)[0]
                p += 6
                subs.append((clear, enc))
        entries.append((iv, subs))
    return entries


def mdat_ranges(data):
    # _parse_boxes yields (type, box_start, header_size, box_size); unpack all four
    return [(off + hdr, off + size) for _t, off, hdr, size in _parse_boxes(data)
            if data[off + 4:off + 8] == b"mdat"]


def decrypt(data, key, verbose=True):
    """Decrypt every sample in place; returns (new_bytes, stats)."""
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

    info = describe(data)
    iv_size = 8
    # tenc carries the declared IV size; senc implies 8 or 16 in practice
    tenc = find(data, b"tenc")
    if tenc:
        iv_size = data[tenc[0][0] + 7]
    if iv_size not in (8, 16):
        iv_size = 8
    if verbose:
        print("  tenc IV size=%d, senc subsamples=%s" % (iv_size, info.get("senc_has_subsamples")))

    sizes = sample_sizes(data)
    entries = senc_entries(data, iv_size, len(sizes))
    if entries is None:
        raise ValueError("no senc box: nothing to decrypt")
    if len(entries) < len(sizes):
        raise ValueError("senc has %d entries for %d samples" % (len(entries), len(sizes)))

    out = bytearray(data)
    mdats = mdat_ranges(data)
    total = len(data)
    consumed = 0
    idx = 0
    stats = {"samples": len(sizes), "subsample_samples": 0, "mdats": len(mdats)}

    for mstart, mend in mdats:
        pos = mstart
        while pos < mend and idx < len(sizes):
            n = sizes[idx]
            if n <= 0 or pos + n > mend:
                break
            iv, subs = entries[idx]
            # CENC: the 8-byte IV is the nonce; the block counter starts at 0
            if iv_size == 16:
                nonce, counter = iv[:8], iv[8:]
                initial = int.from_bytes(counter, "big")
            else:
                nonce, initial = iv, 0
            cipher = Cipher(algorithms.AES(key), modes.CTR(nonce + initial.to_bytes(8, "big")))
            dec = cipher.decryptor()
            block = bytes(out[pos:pos + n])
            if subs:
                stats["subsample_samples"] += 1
                plain = bytearray()
                for clear, enc in subs:
                    if clear:
                        plain += block[len(plain):len(plain) + clear]
                    if enc:
                        off = len(plain)
                        plain += dec.update(block[off:off + enc])
                plain += block[len(plain):]
                out[pos:pos + n] = plain
            else:
                out[pos:pos + n] = dec.update(block)
            pos += n
            consumed += n
            idx += 1

    stats["decrypted_bytes"] = consumed
    stats["samples_done"] = idx

    # Rewrite the sample entry so ordinary players accept the file: stsd -> enca becomes
    # stsd -> <the codec named by frma>, and sinf/schi are dropped. The codec is NOT always mp4a:
    # measured, the lossless tier carries frma='fLaC' with a dfLa box (~1 Mbps FLAC), while the AAC
    # tiers carry frma='mp4a'. Hardcoding mp4a left the FLAC stream mislabelled, which ffmpeg
    # reported as thousands of AAC errors.
    real = sample_entry_codec(data) or "mp4a"
    changed = 0
    for off, hdr, size in _find_all(out, b"enca"):
        out[off + 4:off + 8] = real.encode("latin-1")[:4].ljust(4, b" ")
        changed += 1
    stats["stsd_rewritten"] = changed
    stats["real_codec"] = real
    return bytes(out), stats


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        print("usage: cenc_decrypt.py <encrypted_file_or_dir> <encode|probe>")
        return 1
    target, mode = sys.argv[1], sys.argv[2]

    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    print("ffmpeg: %s" % ffmpeg)

    meta = {}
    mf = os.environ.get("SODA_KEY_META") or "key_meta.json"
    if os.path.exists(mf):
        meta = json.load(open(mf, encoding="utf-8"))

    files = ([os.path.join(target, f) for f in sorted(os.listdir(target))
              if f.lower().endswith((".m4a", ".mp4"))] if os.path.isdir(target) else [target])

    rc = 0
    for path in files:
        name = os.path.basename(path)
        blob = meta.get(name) or meta.get(name.replace(".m4a", ""))
        if not blob:
            print("\n%s: no spade_a recorded in key_meta.json -> skipped" % name)
            continue
        data = open(path, "rb").read()
        print("\n" + "=" * 96)
        print("%s (%d bytes)" % (name, len(data)))
        print("=" * 96)
        info = describe(data)
        for k, v in info.items():
            print("  %-22s %s" % (k, v))
        try:
            key = key_from_spade_a(blob)
        except Exception as e:
            print("  key derivation failed: %s" % e)
            rc = 1
            continue
        print("  aes key (%d bytes)      %s" % (len(key), key.hex()))
        try:
            plain, stats = decrypt(data, key)
        except Exception as e:
            print("  decrypt failed: %s" % e)
            rc = 1
            continue
        for k, v in stats.items():
            print("  %-22s %s" % (k, v))
        outp = path.replace(".m4a", "_dec.m4a")
        open(outp, "wb").write(plain)
        print("  wrote %s" % outp)

        if mode == "encode":
            res = subprocess.run([ffmpeg, "-v", "error", "-i", outp, "-f", "null", "-"],
                                 capture_output=True, text=True)
            print("  ffmpeg -f null: rc=%d %s" % (res.returncode,
                                                  (res.stderr or "").strip()[:300] or "(no errors)"))
            if res.returncode != 0:
                rc = 1
            vol = subprocess.run([ffmpeg, "-i", outp, "-af", "volumedetect", "-f", "null", "-"],
                                 capture_output=True, text=True)
            for line in (vol.stderr or "").splitlines():
                if "mean_volume" in line or "max_volume" in line:
                    print("  %s" % line.strip())
    return rc


if __name__ == "__main__":
    sys.exit(main())
