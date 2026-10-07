"""Soda Fetch -- the DPmusic-facing interface.

    python soda_fetch.py --track <id> [--quality lossless|hi_res|spatial|highest|higher|medium]
                         [--download] [--json out.json]

How it works (all measured):
  1. the client is cold-started with  luna:///playing?track_id=<id>&media_type=track
     -> it resolves the track through its OWN authenticated session (signature + login)
  2. while it plays, the player holds a per-tier quality ladder in memory, each tier being
        <CDN url>","video_meta":{"quality":"...","bitrate":N,"codec_type":"flac|aac","size":N}
     we read that ladder and pick the requested tier
  3. the CDN url is fetched with NO signing, NO cookie, NO special UA (verified)

Quality notes (measured):
  * 'lossless' = codec_type 'flac' (16- or 24-bit)
  * 'hi_res' / 'spatial' = codec_type 'aac' (a different master, not higher bit depth)
  * anonymous endpoints cap out around br=251 and never yield lossless -- this path needs the
    logged-in client.
"""
import argparse, glob, hashlib, json, os, re, shutil, ssl, struct, subprocess, sys, time
import urllib.error, urllib.request

# Use the OFFICIAL installation. An extracted copy of the app leaves only 2 processes
# and an error window "异常诊断" (its own app.asar is byte-identical, so the difference is the
# installer-provided layout/metadata), whereas the official copy reaches 7 processes and a working
# window. Its logging also differs: it does NOT emit the acceptRequest/handleRequest lines, so tier
# detection must be memory-based with duration verification (which is what this module does).
# The client's install directory and its cache. Override with SODA_HOME when the client is
# installed elsewhere (or for a different version directory).
SODA_HOME = os.environ.get("SODA_HOME") or os.path.join(
    os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "Programs", "Soda Music", "3.8.0")
EXE = os.environ.get("SODA_EXE") or os.path.join(SODA_HOME, "SodaMusic.exe")
CWD = SODA_HOME
# The client's play cache. Its keys look like <vid>_F_<quality>, which is how a fresh cache is
# bound to the track just played.
CACHE = os.environ.get("SODA_CACHE") or os.path.join(
    os.environ.get("APPDATA", os.path.expanduser("~")), "SodaMusic", "LunaCacheV2")
UA = "LunaPC/3.8.0(467160162)"
TIER_ORDER = ["lossless", "hi_res", "spatial", "immersive", "highest", "higher", "medium"]

AGENT = r"""
'use strict';
var out = { windows: [] };
var TID = null;
function hexOf(s){ var a=[]; for(var i=0;i<s.length;i++) a.push(('0'+s.charCodeAt(i).toString(16)).slice(-2)); return a.join(' '); }
function txt(addr,n){ try { var b=new Uint8Array(addr.readByteArray(n)); var s='';
  for(var i=0;i<b.length;i++){ var c=b[i]; s += (c>=32&&c<127)?String.fromCharCode(c):' '; } return s; } catch(e){ return null; } }
rpc.exports = {
  setTid: function(t){ TID = t; out.windows = []; return TID; },
  run: function(){
    // Reset per scan. Without this the array accumulates across calls -- observed growing
    // 85 -> 197 -> 323 -> 446 -> 546 -> 640 over six scans -- which inflates dump() and makes any
    // "has the ladder stabilised" test impossible, because the window count rises monotonically
    // even when the identified ladder never changes.
    out.windows = [];
    var t = TID;
    if (!t) return JSON.stringify({n: 0, err: 'no tid'});
    // Anchor on the TRACK ID and read a window around it: the media-detail object holds both the id
    // and the whole play_info ladder, so a window containing the id we asked the client to play
    // cannot be another track's data.
    //
    // Rejected approaches and why:
    //   * scanning `"video_meta"` and merging every process by max bitrate -> other tracks'
    //     memory-mapped ladders win, producing byte-identical downloads for different ids
    //   * a fixed [addr-2400, addr] window ending at the tag -> missed the url written before it
    //   * filtering blobs by whether some anchor vid appears -> unreliable
    //
    // The distinct-id count is recorded because the client PRELOADS the following track, so several
    // ladders coexist in memory; a window mentioning only our id cannot belong to someone else.
    Process.enumerateRanges('rw-').forEach(function(rg){
      if (rg.size > 240*1024*1024) return;
      var hits = [];
      try { hits = Memory.scanSync(rg.base, rg.size, hexOf(t)); } catch(e) { return; }
      // 12 hits x 50 KB is sufficient: the ladder sits immediately beside the id, and a wider
      // sweep only made each scan slower.
      hits.slice(0, 12).forEach(function(x){
        var s = txt(x.address.sub(25000), 50000);
        if (!s) return;
        var ids = {};
        var re2 = /"(?:id|track_id|media_id|group_id)"\s*:\s*"(\d{15,20})"/g, m2;
        while ((m2 = re2.exec(s))) ids[m2[1]] = 1;
        var n = 0; for (var k in ids) n++;
        out.windows.push({ s: s, ids: n, own: !!ids[t] });
      });
    });
    return JSON.stringify({n: out.windows.length});
  },
  dump: function(){ return JSON.stringify(out); }
};
"""
def _soda_fetch_doc():
    """Module notes (kept as a function docstring so the text cannot be parsed as code)."""
    return """

    python soda_fetch.py --track <id> [--quality lossless|hi_res|spatial|highest|higher|medium]
                         [--download] [--json out.json]

How it works (all measured):
  1. the client is cold-started with  luna:///playing?track_id=<id>&media_type=track
     -> it resolves the track through its OWN authenticated session (signature + login)
  2. while it plays, the player holds a per-tier quality ladder in memory, each tier being
        <CDN url>","video_meta":{"quality":"...","bitrate":N,"codec_type":"flac|aac","size":N}
     we read that ladder and pick the requested tier
  3. the CDN url is fetched with NO signing, NO cookie, NO special UA (verified)

Quality notes (measured):
  * 'lossless' = codec_type 'flac' (16- or 24-bit)
  * 'hi_res' / 'spatial' = codec_type 'aac' (a different master, not higher bit depth)
  * anonymous endpoints cap out around br=251 and never yield lossless -- this path needs the
    logged-in client.
"""

# `out` is declared above as { windows: [] }

#  {"main_url":"<url>", ... "video_meta":{"quality":"q","vtype":"..","bitrate":N,...}
URL_ANCHOR = re.compile(r'main_url"?\s*:\s*"(https://[A-Za-z0-9._\-]+/[^\s"]{40,1200})')
META_AFTER = re.compile(
    r'"quality"\s*:\s*"(\w+)"\s*,\s*"vtype"\s*:\s*"(\w+)"'
    r'(?:\s*,\s*"bitrate"\s*:\s*(\d+))?'
    r'(?:\s*,\s*"codec_type"\s*:\s*"(\w+)")?'
    r'(?:\s*,\s*"size"\s*:\s*(\d+))?')

# The tier object also carries the CENC key material, and the client NAMES IT `spade_a` -- not
# `play_auth`. Verified against the live client: each tier object is
#   {"main_url":...,"backup_url":...,"video_meta":{"quality":"hi_res",...},
#    "encrypt_info":{"encrypt":true,"kid":"...","spade_a":"...","encryption_method":"cenc-aes-ctr"},
#    "volume":{...},"gear_des_key":"0:M4A|1:audio_encrypt|..."}
# and without it the samples cannot be decrypted (they are CENC AES-CTR, stsd=enca + senc).
# The `spade_a` MUST be the one from the SAME object as the url: every tier has its own kid, and a
# mismatched key decrypts to noise (measured).
ENCRYPT_AFTER = re.compile(
    r'"encrypt_info"\s*:\s*\{[^}]*?"encrypt"\s*:\s*(true|false)'
    r'(?:[^}]*?"kid"\s*:\s*"([^"]*)")?'
    r'(?:[^}]*?"spade_a"\s*:\s*"([^"]*)")?'
    r'(?:[^}]*?"encryption_method"\s*:\s*"([^"]*)")?', re.S)

# the key derivation (play_auth -> AES key) is only meaningful when the blob is a real value
SPADE_KEY_RE = re.compile(r'"spade_a"\s*:\s*"([^"]+)"')
KID_RE = re.compile(r'"kid"\s*:\s*"([^"]+)"')
EM_RE = re.compile(r'"encryption_method"\s*:\s*"([^"]+)"')
FRMA_RE = re.compile(r'frma')


def parse_encrypt_info(window_text, start_pos, span=1400):
    """Extract the encrypt_info block that belongs to the url at `start_pos`.

    The block sits a fixed distance after the url inside the SAME tier object, so the search is
    bounded by the next `main_url` -- that bound is what keeps one tier's key from being attached to
    another tier's url.
    """
    nxt = window_text.find("main_url", start_pos + 1)
    end = min(nxt, start_pos + span) if nxt > 0 else start_pos + span
    seg = window_text[start_pos:end]
    ci = ENCRYPT_AFTER.search(seg)
    if not ci:
        return None
    out = {"encrypt": ci.group(1) == "true"}
    spa = SPADE_KEY_RE.search(seg)
    kid = KID_RE.search(seg)
    em = EM_RE.search(seg)
    if spa:
        out["spade_a"] = spa.group(1)
    if kid:
        out["kid"] = kid.group(1)
    if em:
        out["encryption_method"] = em.group(1)
    return out


def _tiers_from_hits(hits):
    tiers = {}
    for s in hits:
        for m in URL_ANCHOR.finditer(s):
            url = m.group(1)
            if not re.search(r"[?&]br=\d+", url):
                continue
            mm = META_AFTER.search(s[m.end():m.end() + 900])
            if not mm:
                continue
            q = mm.group(1)
            rec = {
                "quality": q, "url": url, "vtype": mm.group(2),
                "br": int(re.search(r"[?&]br=(\d+)", url).group(1)),
                "mime": (re.search(r"[?&]mime_type=([a-z_0-9]+)", url) or [None, None])[1],
                "codec_type": mm.group(4),
                "declared_bitrate": int(mm.group(3) or 0),
                "declared_size": int(mm.group(5) or 0),
                "obj": (re.search(r"tos-cn-ve-\d+/([A-Za-z0-9]{16,})", url) or [None, None])[1],
                "source": "memory-ladder",
            }
            cur = tiers.get(q)
            if cur is None or rec["declared_bitrate"] > (cur["declared_bitrate"] or 0):
                tiers[q] = rec
    return tiers


def fresh_cache_sizes():
    """Byte sizes of the play-cache blobs written during THIS run.

    The cache is cleared before every play, so each *.bin that appears afterwards is media the
    client fetched for this track. Its size equals that tier's `size` field, which gives an
    independent identity check on a memory-read ladder (and catches another track's ladder,
    which is what previously produced byte-identical downloads).
    """
    sizes = set()
    if not os.path.isdir(CACHE):
        return sizes
    for fn in os.listdir(CACHE):
        if not fn.endswith(".bin"):
            continue
        try:
            sizes.add(os.path.getsize(os.path.join(CACHE, fn)))
        except Exception:
            pass
    return sizes



def fresh_cache_vids():
    """vids present in the client's play cache AFTER the cache was cleared before this play.

    This is how we bind a track id to its media ids: LunaCacheV2 keys look like
    <vid>_F_<quality>, and since the cache was emptied right before playing, whatever appears
    there belongs to the track we just asked for.
    """
    db = os.path.join(CACHE, "entries.db")
    if not os.path.exists(db):
        return []
    raw = open(db, "rb").read()
    return sorted(set(k.decode() for k in re.findall(
        rb"(v[0-9a-z]{20,40})_F_(?:medium|higher|highest|lossless|spatial|hi_res|immersive)",
        raw)))


def log_run_urls(log_path):
    """URLs the client actually fetched during THIS run, taken from its own stdout.

    The log is truncated for every run, so unlike memory it cannot contain leftovers from
    earlier tracks. It therefore serves as the anchor that tells us which memory-resident
    ladder belongs to the track we just started.
    """
    if not os.path.exists(log_path):
        return set(), {}
    t = open(log_path, "rb").read().decode("utf-8", "replace")
    urls = set()
    for m in re.finditer(r"https://[A-Za-z0-9._\-]+\.douyinvod\.com/[^\s\"']{40,1500}", t):
        u = m.group(0).rstrip(".,)")
        urls.add(u)
    byvid = {}
    for m in re.finditer(r"acceptRequest\s+(v[0-9a-z]{20,40})_F_(\w+)", t):
        byvid.setdefault(m.group(1), set()).add(m.group(2))
    return urls, byvid


def tiers_from_log(log_path):
    """tier -> url, from the client's stdout for THIS run, preserving order.

    The client logs, in playback order:
        handleRequest... <full cdn url>
        newswtask load   <full cdn url>
        acceptRequest <vid>_F_<quality>

    Because the log is truncated per run and the client PRELOADS following tracks, several tiers
    appear, so only the FIRST occurrence of each quality is kept: the first one belongs to the
    track currently playing (later ones belong to preloaded tracks).

    `acceptRequest` is what proves the client actually chose that tier for this run -- a
    `tryresume` line may merely be resuming cache from an earlier session.
    """
    if not log_path or not os.path.exists(log_path):
        return {}
    t = open(log_path, "rb").read().decode("utf-8", "replace")
    ev = []
    for m in re.finditer(r"handleRequest\.\.\.\s*(\S+)|acceptRequest\s+(\S+)", t):
        if m.group(1):
            ev.append(("url", m.group(1)))
        else:
            ev.append(("acc", m.group(2)))
    out = {}
    last = None
    for kind, val in ev:
        if kind == "url":
            last = val
            continue
        vm = re.match(r"(v[0-9a-z]{20,40})_F_(\w+)", val)
        if not vm or not last:
            continue
        q = vm.group(2)
        if q in out:                      # keep the first -> the current track, not the preload
            continue
        if not re.search(r"[?&]br=\d+", last):
            continue
        out[q] = {
            "quality": q, "url": last,
            "mime": (re.search(r"[?&]mime_type=([a-z_0-9]+)", last) or [None, None])[1],
            "br": int(re.search(r"[?&]br=(\d+)", last).group(1)),
            "codec_type": "flac" if q == "lossless" else None,
            "declared_bitrate": None, "declared_size": None,
            "obj": (re.search(r"tos-cn-ve-\d+/([A-Za-z0-9]{16,})", last) or [None, None])[1],
            "vid": vm.group(1),
            "source": "client-log",
        }
    return out


def log_segments(log_path):
    """CDN issue segments of the media the client provably fetched during THIS run.

    The log is truncated per run, so these segments belong to the track we just asked for. They
    identify which memory-resident ladder is ours -- necessary because a previous track's complete
    ladder stays readable in memory and otherwise wins on size.
    """
    if not log_path or not os.path.exists(log_path):
        return set()
    t = open(log_path, "rb").read().decode("utf-8", "replace")
    segs = set()
    for m in re.finditer(r"https://[A-Za-z0-9._\-]+\.douyinvod\.com/[^\s\"']{40,1500}", t):
        s = _seg(m.group(0))
        if s:
            segs.add(s)
    return segs


def catalog_info(track_id):
    """Catalogue metadata for a track: duration plus the membership markers.

    The markers matter for the real requirement ("member songs must play IN FULL"):
        only_vip_playable  true  -> the track is member-only for full playback
        only_vip_download  true  -> member-only for download
        need_vip           true  -> requires VIP
        preview            {...} -> preview clip block
    A logged-in SVIP account still receives full-length media for these (measured 7/7), so the
    markers are informational rather than a blocker -- but they are what tells a caller whether a
    short result would be a preview clip or a different track entirely.

    Returns {} when the catalogue cannot be read, so callers never treat missing data as "free".
    """
    import ssl as _ssl
    import urllib.request as _u
    url = "https://api.qishui.com/luna/h5/seo_track?track_id=%s" % track_id
    try:
        rq = _u.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with _u.urlopen(rq, timeout=25, context=_ssl.create_default_context()) as r:
            body = r.read().decode("utf-8", "replace")
    except Exception:
        return {}
    out = {}
    for pat in (r'duration\\?"\s*:\s*(\d{5,8})', r'"duration"\s*:\s*(\d{5,8})'):
        m = re.search(pat, body)
        if m:
            v = int(m.group(1))
            if 10_000 < v < 3_600_000:
                out["duration_s"] = v / 1000.0
            break
    for k in ("only_vip_playable", "only_vip_download", "need_vip", "is_vip"):
        m = re.search(r'"%s"\s*:\s*(true|false)' % k, body)
        if m:
            out[k] = (m.group(1) == "true")
    m = re.search(r'"preview"\s*:\s*(\{[^{}]{2,400}\})', body)
    if m:
        out["preview"] = m.group(1)[:300]
    return out


def catalog_duration(track_id):
    """Official duration (seconds) of a track, from the public SEO endpoint.

    Used as the authoritative check on whether a candidate URL actually holds THIS track. It is the
    only cue that stayed reliable: byte hashes and `acceptRequest` both misled earlier (the client
    preloads following tracks and issues acceptRequest for them too, even at lossless).
    Returns None when the value cannot be read, in which case callers must not claim verification.
    """
    import ssl as _ssl
    import urllib.request as _u
    ctx = _ssl.create_default_context()
    url = "https://api.qishui.com/luna/h5/seo_track?track_id=%s" % track_id
    try:
        rq = _u.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with _u.urlopen(rq, timeout=25, context=ctx) as r:
            text = r.read().decode("utf-8", "replace")
    except Exception:
        return None
    for pat in (r'duration\\?"\s*:\s*(\d{5,8})', r'"duration"\s*:\s*(\d{5,8})'):
        m = re.search(pat, text)
        if m:
            v = int(m.group(1))
            if 10_000 < v < 3_600_000:          # 10 s .. 60 min, sanity
                return v / 1000.0
    return None


def media_duration(url, timeout=45):
    """Duration of the media behind a CDN url, from the first 256 KB (Range request).

    FLAC: STREAMINFO. AAC: mvhd. Cheap enough to use as a per-candidate verification step.
    """
    import ssl as _ssl
    import urllib.request as _u
    ctx = _ssl.create_default_context()
    try:
        rq = _u.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-262143"})
        with _u.urlopen(rq, timeout=timeout, context=ctx) as r:
            head = r.read()
    except Exception:
        return None
    d = head.find(b"dfLa")
    if d >= 0:
        si = head[d + 12:d + 46]
        if len(si) >= 34:
            x = int.from_bytes(si[10:18], "big")
            rate = (x >> 44) & 0xFFFFF
            if rate:
                return round((x & 0xFFFFFFFFF) / rate, 2)
    m = head.find(b"mvhd")
    if m >= 0:
        ts = struct.unpack(">I", head[m + 16:m + 20])[0]
        du = struct.unpack(">I", head[m + 20:m + 24])[0]
        if ts:
            return round(du / ts, 2)
    return None


def verify_group_duration(tiers, want_s, tol=2.5):
    """True when any tier of this group decodes to the wanted duration.

    This is what identifies the group that belongs to the requested track: the client preloads
    following tracks, so several real ladders coexist and only the content itself can tell them
    apart. Measured case: for a 140.7 s track the client fetched 'highest' (140.6 s -> our track)
    and 'lossless' (160.0 s -> a different, preloaded track).
    """
    if not want_s:
        return False
    for q in ("highest", "lossless", "higher", "medium", "hi_res", "spatial"):
        t = tiers.get(q)
        if not t:
            continue
        d = media_duration(t["url"])
        if d is None:
            continue
        t["duration_s"] = d
        if abs(d - want_s) <= tol:
            return True
    return False


def extract_ladder(tid=None, log_path=None, tries=6, require=None, verbose=False):
    """Return the per-tier CDN ladder for the track we just started.

    Two independent sources, cross-checked:

      A) the client's own stdout for THIS run -- exact, because the log is truncated per run;
      B) the play_info ladder in memory, anchored on the literal `main_url` and verified against
         the byte sizes of the play-cache blobs written during this run.

    Why the verification matters: memory-mapped caches keep earlier tracks readable, so a bare
    memory scan can return another track's ladder -- which once produced byte-identical downloads
    for different track ids.
    """
    import frida

    from_log = tiers_from_log(log_path) if log_path else {}
    cache_sizes = fresh_cache_sizes()
    if not tid:
        return from_log

    dev = frida.get_local_device()
    windows = []
    groups = {}
    log_segs = log_segments(log_path)

    # Attach ONCE and keep the script resident, re-scanning through it.
    #
    # The previous version re-attached and re-loaded a fresh agent for every attempt, which is both
    # slow (each attach + load costs ~1.5 s) and destructive: repeated attach/detach was measured to
    # fail with "unexpected error allocating memory in target process (VirtualAllocEx)" and then kill
    # the main process outright ("unable to find process with pid ..."). One resident session avoids
    # both problems and is what makes polling for the ladder cheap.
    sess = None
    sc = None
    for pr in dev.enumerate_processes():
        if pr.name.lower() != "sodamusic.exe":
            continue
        try:
            s = dev.attach(pr.pid)
            probe = s.create_script(
                "rpc.exports={m:function(){return Process.findModuleByName('main.node')?'y':'n'}}")
            probe.load()
            is_main = probe.exports_sync.m() == "y"
            probe.unload()
            if not is_main:
                s.detach()
                continue
            c = s.create_script(AGENT)
            c.on("message", lambda m, d: None)
            c.load()
            c.exports_sync.setTid(tid)
            sess, sc = s, c
            break
        except Exception:
            continue

    if sc is not None:
        try:
            prev_sig = None
            stagnant = 0
            for attempt in range(tries):
                try:
                    sc.exports_sync.run()
                    d = json.loads(sc.exports_sync.dump())
                    fresh = [w for w in (d.get("windows") or []) if isinstance(w, dict)]
                except Exception:
                    fresh = []
                windows = fresh
                groups = {}
                _merge_groups(groups, _groups_from_windows(fresh, tid))
                mem = _best_group(groups, log_segs, cache_sizes)
                if verbose:
                    print("   attempt %d: windows=%d groups=%d log_segs=%d best=%s" % (
                        attempt + 1, len(windows), len(groups), len(log_segs), sorted(mem)),
                        flush=True)

                # Stop as soon as the answer can no longer improve.
                #
                # The old test was `require in mem`, which NEVER becomes true when the track does
                # not offer the requested tier -- so for a track with only higher/highest/medium it
                # ran all six rounds regardless (measured: 80.6 s for one such track, the reason a
                # CLI fetch took 121 s while another took 22.9 s). Two conditions are checked now:
                #   * the requested tier is present -> done; or
                #   * it cannot appear, because the ladder already has every tier the client will
                #     resolve, i.e. what we hold is a superset of what is available, and has stopped
                #     growing across two scans.
                if require and require in mem:
                    break
                if not require and len(mem) >= 6:
                    break
                # tiers a play_info response can carry; a ladder that covers all of them is final
                ALL_TIERS = {"lossless", "hi_res", "spatial", "highest", "higher", "medium"}
                # ONLY the ladder. The count of id occurrences in memory drifts with
                # ordinary allocator churn (measured 93,108,112,116,119,103 across six scans
                # for one unchanged ladder), so including it never let the test succeed.
                signature = frozenset(mem)
                if require and not (mem.keys() & {require}):
                    # 'require' absent: only conclude once the ladder is complete or has stopped
                    # changing, so a tier that appears one scan later is not missed.
                    if ALL_TIERS.issubset(set(mem)) or (signature == prev_sig and attempt >= 1):
                        if verbose:
                            print("   '%s' is not offered by this track; ladder is stable (%s)"
                                  % (require, sorted(mem)), flush=True)
                        break
                prev_sig = signature
                if attempt + 1 >= tries:
                    break
                # Short gap. The ladder is written once per resolution shortly after playback starts
                # (the client logs "successfully started playing" ~4.5 s after launch), and the scan
                # is a whole-process memory walk, so a long sleep buys nothing but latency -- the old
                # 7 s interval was the bulk of the extraction cost.
                time.sleep(1.5)
        finally:
            try:
                sess.detach()
            except Exception:
                pass
    else:
        mem = {} if not from_log else from_log

    # Identify the group belonging to OUR track by CONTENT: decode each candidate group's duration
    # and compare with the catalogue duration of the id we asked for. This is the only discriminator
    # that held -- acceptRequest fires for preloaded tracks too (measured: a 140.7 s track came back
    # as a 160.0 s FLAC), and memory windows/caches mix several tracks.
    want = catalog_duration(tid) if tid else None
    own_group = None
    if want:
        ranked = sorted(groups.values(),
                        key=lambda g: -(g.get("purity") if g.get("purity") is not None else 999))
        for g in ranked:
            if verify_group_duration(g["tiers"], want):
                own_group = g["tiers"]
                if verbose:
                    print("   identified our track's group by duration (catalogue %.1f s)" % want,
                          flush=True)
                break
    if own_group is None:
        # fall back to the previous heuristics, but nothing will be marked confirmed
        own_group = _best_group(groups, log_segs, cache_sizes)

    mem = own_group

    if verbose:
        print("   tiers=%s  catalogue_dur=%s" % (sorted(mem), want), flush=True)

    # Everything from that group is verified to be our track (its duration matched), so all of its
    # tiers may be reported as confirmed.
    if want and own_group:
        for t in mem.values():
            t["confirmed"] = True
        return mem

    # No duration available: cannot verify ownership, so report the log-derived tiers as unconfirmed.
    for t in (from_log or {}).values():
        t["confirmed"] = False
    for t in mem.values():
        t["confirmed"] = False
    out = dict(from_log or {})
    for q, t in mem.items():
        out.setdefault(q, t)
    return out

    merged = dict(from_log)
    for q, t in mem.items():
        if q in merged:
            continue
        merged[q] = t
    return merged


def _groups_from_windows(windows, own_id=None):
    """Group tier entries by CDN issue segment, keeping each group's 'purity' score.

    A window's `ids` field counts how many DISTINCT track ids appear inside it. The purest window
    that still mentions our own id is the best evidence of our track's own ladder, because the
    client preloads the next track and other ladders are therefore present in memory.
    """
    groups = {}
    for w in windows:
        s = w.get("s") if isinstance(w, dict) else w
        purity = w.get("ids") if isinstance(w, dict) else None
        if not s:
            continue
        for m in URL_ANCHOR.finditer(s):
            url = m.group(1)
            if not re.search(r"[?&]br=\d+", url):
                continue
            # URL shape: https://host/<32-hex sig>/<8-hex issue>/video/tos/...
            # All tiers of one track share the 8-hex issue segment.
            seg = re.search(r"\.com/[0-9a-f]{16,40}/([0-9a-f]{8})/", url)
            if not seg:
                continue
            mm = META_AFTER.search(s[m.end():m.end() + 900])
            if not mm:
                continue
            rec = {
                "quality": mm.group(1), "url": url, "vtype": mm.group(2),
                "br": int(re.search(r"[?&]br=(\d+)", url).group(1)),
                "mime": (re.search(r"[?&]mime_type=([a-z_0-9]+)", url) or [None, None])[1],
                "codec_type": mm.group(4),
                "declared_bitrate": int(mm.group(3) or 0),
                "declared_size": int(mm.group(5) or 0),
                "obj": (re.search(r"tos-cn-ve-\d+/([A-Za-z0-9]{16,})", url) or [None, None])[1],
                "issue": seg.group(1),
                "source": "memory-ladder",
            }
            # attach THIS object's CENC key material (see ENCRYPT_AFTER above)
            enc = parse_encrypt_info(s, m.end())
            if enc:
                rec["encrypt_info"] = enc
            g = groups.setdefault(seg.group(1), {"tiers": {}, "purity": None, "own": False})
            if purity is not None and (g["purity"] is None or purity < g["purity"]):
                g["purity"] = purity
            if isinstance(w, dict) and w.get("own"):
                g["own"] = True
            cur = g["tiers"].get(rec["quality"])
            if cur is None or rec["declared_bitrate"] > (cur["declared_bitrate"] or 0):
                g["tiers"][rec["quality"]] = rec
            else:
                # keep whichever copy has the key material: the same tier can appear in several
                # windows, and only some of them reach far enough to include encrypt_info
                if rec.get("encrypt_info") and not cur.get("encrypt_info"):
                    cur["encrypt_info"] = rec["encrypt_info"]
    return groups


def _merge_groups(dst, src):
    for seg, g in src.items():
        d = dst.setdefault(seg, {"tiers": {}, "purity": None, "own": False})
        if g.get("purity") is not None and (d["purity"] is None or g["purity"] < d["purity"]):
            d["purity"] = g["purity"]
        d["own"] = d["own"] or g.get("own", False)
        for q, rec in g["tiers"].items():
            cur = d["tiers"].get(q)
            if cur is None or rec["declared_bitrate"] > (cur["declared_bitrate"] or 0):
                d["tiers"][q] = rec
            elif rec.get("encrypt_info") and not cur.get("encrypt_info"):
                cur["encrypt_info"] = rec["encrypt_info"]


def _seg(url):
    """CDN issue segment of a media url: all tiers signed together share it."""
    m = re.search(r"\.com/[0-9a-f]{16,40}/([0-9a-f]{8})/", url or "")
    return m.group(1) if m else None


def _best_group(groups, log_segs=None, cache_sizes=None):
    """Pick the ladder belonging to the track we played.

    Priority, and the reason for it:

      1. PUREST group that mentions our own id -- the lowest number of distinct track ids in the
         windows it came from. This is the criterion that held in practice: the client preloads the
         next track, so memory holds several ladders, and looser rules ("largest ladder",
         "nearest main_url", "segment present in the log") each picked the wrong one at least once
         -- two genuinely different songs (162 s and 93 s) downloaded byte-identical files.
      2. else a group whose issue segment the client fetched this run.
      3. else the group with most cache-size matches.
      4. else the most complete group.
    """
    if not groups:
        return {}

    own = [g for g in groups.values() if g.get("own")]
    if own:
        ranked = sorted(own, key=lambda g: ((g.get("purity") if g.get("purity") is not None else 999),
                                            -len(g["tiers"])))
        return ranked[0]["tiers"]

    tier_map = {seg: g["tiers"] for seg, g in groups.items()}
    if log_segs:
        hits = [t for seg, t in tier_map.items() if seg in log_segs]
        if hits:
            return max(hits, key=lambda t: (len(t),
                                            sum(x.get("declared_size") or 0 for x in t.values())))
    if cache_sizes:
        def matched(tiers):
            return sum(1 for x in tiers.values()
                       if x.get("declared_size") and x["declared_size"] in cache_sizes)
        best = max(tier_map.values(), key=lambda t: (matched(t), len(t)))
        if matched(best):
            return best
    return max(tier_map.values(),
               key=lambda t: (len(t), sum(x.get("declared_size") or 0 for x in t.values())))


def set_play_quality(quality="lossless"):
    """Write the play quality into the client's config store before starting it.

    Required, not cosmetic: by default the client never fetches lossless. At startup its commerce
    refresh reads the not-yet-loaded player quality (the default 'higher', which is absent from the
    server's list medium/highest/lossless/spatial/hi_res), so `checkQualityBenefit` fails and it
    falls back to 'highest' -- the log line
        "current quality not available due to quality benefit changed. switch to highest"
    appears in every run. With the value present in the store the client instead issues
        acceptRequest <vid>_F_lossless
    and pulls the lossless tier.

    Only selects a tier the account already holds (verified against the server's quality_list);
    it does not grant anything.
    """
    import gzip, glob, json as _json

    ls = os.path.join(os.path.dirname(CACHE), "LunaStorage")
    cfgp = os.path.join(ls, "Config")
    if not os.path.exists(cfgp):
        return None

    def dec(p):
        raw = open(p, "rb").read()
        if raw[:4] != b"LUNA":
            return None
        return _json.loads(gzip.decompress(raw[4:]).decode("utf-8"))

    def enc(o, p):
        body = _json.dumps(o, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        with open(p, "wb") as f:
            f.write(b"LUNA")
            f.write(gzip.compress(body, 9, mtime=0))

    try:
        base = dec(cfgp)
        uid = base["userInfoStateCache"]["my_info"]["id"]
        avail = [x["key"] for x in base["commerceState"]["commerceInfo"]["benefit_base"]
                 ["quality"]["quality_list"]]
    except Exception:
        return None
    if quality not in avail:
        return None

    n = 0
    for p in glob.glob(os.path.join(ls, "Config*")):
        if not os.path.isfile(p) or os.path.getsize(p) == 0:
            continue
        # A previous run may have left these read-only (a lock experiment); writing to a
        # read-only file fails silently inside the try/except below, which cost a whole
        # investigation once -- so clear the attribute first.
        try:
            import ctypes
            ctypes.windll.kernel32.SetFileAttributesW(str(p), 128)
        except Exception:
            pass
        try:
            o = dec(p)
        except Exception:
            continue
        if o is None:
            continue
        o["playQuality"] = {"guest": quality, uid: quality}
        try:
            enc(o, p)
            n += 1
        except Exception:
            pass
    return n


def play(track_id, wait=42, clear_cache=True, quality="lossless"):
    """Start the client on one track and return once playback is actually under way.

    `wait` is now an UPPER BOUND, not a fixed sleep: the function watches the client's own log for
    the line that means playback has begun, and returns immediately after it. Measured, that line
    appears ~4.5 s after launch (first log line t=0.0, deeplink handled +1.6 s, "successfully started
    playing" +4.5 s), whereas the previous blind sleep always burned the full 42 s -- which was the
    single largest cost of a fetch (31% of 134 s in the measured profile).

    clear_cache defaults to True on purpose: extract_ladder() binds a track to its media ids via
    the play cache, and entries.db is memory-mapped (old tracks stay visible), so a stale cache
    silently yields the PREVIOUS track's ladder -- two different tracks once downloaded
    byte-identical files.
    """
    # Prevent the 异常诊断 dialog before launching. src/utils/safeMode.ts counts files in
    # %TEMP%\SodaMusic_Launch_Records newer than now-10min and shows the dialog at 4 or more; its
    # default button is 进入安全模式, which cleans userData and forces a re-login. Called here so
    # every entry point that starts the client is covered, not just the daemon.
    try:
        import soda_guard
        _g = soda_guard.find_dialog()
        if _g is not None:
            soda_guard.dismiss("soda_fetch.play")
        else:
            soda_guard.prevent(verbose=False)
    except Exception:
        pass
    os.system("taskkill /F /IM SodaMusic.exe >nul 2>&1")
    time.sleep(3)
    if clear_cache:
        shutil.rmtree(CACHE, ignore_errors=True)
    os.makedirs(CACHE, exist_ok=True)
    if quality:
        set_play_quality(quality)
    env = {k: v for k, v in os.environ.items() if k != "ELECTRON_RUN_AS_NODE"}
    log = os.path.join(os.environ.get("SODA_LOG_DIR") or os.environ.get("TEMP") or ".",
                        "soda_fetch_%s.log" % track_id)
    t0 = time.perf_counter()
    subprocess.Popen([EXE, "--", "luna:///playing?track_id=%s&media_type=track" % track_id],
                     cwd=CWD, env=env, stdout=open(log, "wb"), stderr=subprocess.STDOUT)

    # Return as soon as the client says it is playing. "resumed playing current track" counts too:
    # the queue cache can make the client resume instead of re-resolving, and both mean the player
    # is live and the ladder is being written.
    READY = ("successfully started playing", "resumed playing current track")
    deadline = t0 + max(wait, 8)
    while time.perf_counter() < deadline:
        try:
            t = open(log, "rb").read().decode("utf-8", "replace")
        except Exception:
            t = ""
        if any(k in t for k in READY):
            break
        time.sleep(0.4)
    return log


def verify(url, want_bytes=None):
    ctx = ssl.create_default_context()
    rq = urllib.request.Request(url, headers={"User-Agent": UA, "Range": "bytes=0-262143"})
    with urllib.request.urlopen(rq, timeout=60, context=ctx) as r:
        head = r.read()
        total = r.headers.get("Content-Range", "").split("/")[-1]
    info = {"status": 206, "total_bytes": int(total) if total.isdigit() else None,
            "content_type": "video/mp4" if b"ftyp" in head[:64] else "?"}
    d = head.find(b"dfLa")
    if d >= 0:
        si = head[d + 12:d + 46]
        if len(si) >= 34:
            x = int.from_bytes(si[10:18], "big")
            info.update({"codec": "FLAC", "sample_rate": (x >> 44) & 0xFFFFF,
                         "channels": ((x >> 41) & 0x7) + 1, "bits": ((x >> 36) & 0x1F) + 1,
                         "md5": si[18:34].hex()})
            if info["sample_rate"]:
                info["duration_s"] = round((x & 0xFFFFFFFFF) / info["sample_rate"], 2)
    elif b"mp4a" in head:
        info["codec"] = "AAC"
    return info


def main():
    ap = argparse.ArgumentParser(
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""\
RELIABLY OBTAINABLE TIERS (all content-verified by decoded duration)
  hi_res    SVIP, AAC ~325 kbps          <- default
  spatial   SVIP, AAC ~325 kbps, spatial audio
  highest   free,  AAC ~260 kbps
  higher    free,  AAC ~132 kbps
  medium    free,  AAC  ~68 kbps

NOT OBTAINABLE
  lossless  FLAC. The server only includes it in play_info when the client itself asks for
            lossless, and the client cannot be kept on that tier: its shared state starts at the
            hardcoded DEFAULT_QUALITY='higher' (src/services/sharedState/initialState.ts), which is
            absent from the server's quality list, so commerce resets it to 'highest' at every
            launch. Choosing lossless in the UI does persist playQuality['<uid>']='lossless', but
            the menu rows move with the window state, so that is not usable from an interface.
            '--quality lossless' will therefore fall back and say so explicitly.

WHY A DURATION CHECK IS THE ONLY PROOF
  The client preloads the following tracks and its caches are memory-mapped, so several tracks'
  ladders coexist in memory. Byte hashes and the client's acceptRequest lines both misled earlier
  (a 140.7 s track was once delivered as a 160.0 s FLAC). A tier is only reported when its decoded
  audio duration matches the track's catalogue duration.
""")
    ap.add_argument("--track", required=True, help="Soda Music track id")
    ap.add_argument("--quality", default="hi_res", choices=TIER_ORDER,
                    help="preferred tier (default: hi_res, the highest reliably obtainable one)")
    ap.add_argument("--download", action="store_true")
    ap.add_argument("--no-verify", action="store_true")
    # Clearing the play cache is REQUIRED, not an optimisation: with a warm cache the client
    # serves the media straight from it and never re-resolves the play_info ladder, so the
    # per-tier URLs are simply absent from memory (this is why extraction came back empty).
    ap.add_argument("--keep-cache", action="store_true",
                    help="do NOT clear the play cache (will usually break tier extraction)")
    ap.add_argument("--allow-unconfirmed", action="store_true",
                    help="accept a tier that is only OFFERED in memory and not proven to belong "
                         "to this track (may return a different song)")
    ap.add_argument("--json", help="write the result as JSON to this path")
    ap.add_argument("--wait", type=int, default=42)
    a = ap.parse_args()

    # NOTE: earlier versions pre-wrote playQuality into Config here. That is deliberately removed --
    # it never worked (the commerce check overwrites the store on every launch) and the write plus
    # read-only juggling damaged the client's state repeatedly. The tier is now obtained purely by
    # reading what the client itself resolved.
    print("== track %s, preferred quality %s ==" % (a.track, a.quality), flush=True)
    log = play(a.track, a.wait, clear_cache=not a.keep_cache)
    tiers = extract_ladder(a.track, log_path=log, require=a.quality, verbose=True)

    print("\ntiers found in the client: %s" % sorted(tiers, key=lambda q: TIER_ORDER.index(q)
                                                    if q in TIER_ORDER else 99))
    for q in sorted(tiers, key=lambda q: TIER_ORDER.index(q) if q in TIER_ORDER else 99):
        t = tiers[q]
        print("  %-10s codec=%-5s br=%-6s size=%-10s %s" % (
            q, t.get("codec_type") or "-", t.get("br"), t.get("declared_size") or "-",
            "CONFIRMED" if t.get("confirmed") else "unconfirmed"))

    # Choose the requested tier, preferring tiers PROVEN to belong to this track (their URL sits
    # beside our track id in memory). Unconfirmed tiers are only used when the caller explicitly
    # accepts them, because they may belong to a preloaded/earlier track -- which is exactly how
    # wrong songs were previously downloaded.
    confirmed = {q: t for q, t in tiers.items() if t.get("confirmed")}
    pool = confirmed or (tiers if a.allow_unconfirmed else {})
    chosen = None
    if a.quality in pool:
        chosen = pool[a.quality]
    else:
        for q in TIER_ORDER:
            if q in pool:
                chosen = pool[q]
                print("\n  ! '%s' not available for this track (client did not fetch it); "
                      "falling back to '%s'%s"
                      % (a.quality, q, "" if chosen.get("confirmed") else " (UNCONFIRMED)"))
                break
    if not chosen:
        print("\nNo tier for this track is confirmed by the client.")
        if tiers:
            print("  Tiers merely offered in memory (NOT verified for this track):")
            for q in sorted(tiers, key=lambda q: TIER_ORDER.index(q) if q in TIER_ORDER else 99):
                print("     %-10s br=%-6s size=%s" % (q, tiers[q].get("br"),
                                                      tiers[q].get("declared_size") or "-"))
        print("  The client resets its quality to 'highest' at every launch (hardcoded default "
              "'higher' is absent from the server list), so for tracks where it never fetches a\n"
              "  higher tier there is nothing verified to hand back. Re-run with "
              "--allow-unconfirmed to take an unverified tier anyway.")
        print("  client log: %s" % log)
        return 2

    print("\n" + "=" * 96)
    print("CHOSEN: %s%s" % (chosen["quality"],
                            "" if chosen.get("confirmed") else "  (UNCONFIRMED)"))
    print(chosen["url"][:200])
    print("=" * 96)

    result = {"track": a.track, "requested": a.quality, "chosen": chosen, "tiers": tiers,
              "log": log}

    # Content proof. The decoded duration vs the catalogue duration answers two questions at once:
    #   * equal  -> the media really is this track (not a preloaded neighbour)
    #   * equal  -> it is the FULL song, i.e. not a preview clip served for a member-only track
    # Measured: 7/7 member-only tracks (only_vip_playable=true) come back full length while logged
    # in, so the membership markers are reported for information, not as a blocker.
    info = catalog_info(a.track)
    cat = info.get("duration_s") or catalog_duration(a.track)
    dur = media_duration(chosen["url"])
    ours = bool(cat and dur and abs(dur - cat) <= 2.5)
    result["catalogue_s"] = cat
    result["duration_s"] = dur
    result["is_this_track"] = ours
    result["is_full_length"] = ours
    result["membership"] = {k: v for k, v in info.items() if k != "duration_s"}
    vip = info.get("only_vip_playable")
    print("content check: catalogue=%s s  decoded=%s s  -> %s"
          % (cat, dur, "FULL LENGTH" if ours else
             ("NOT VERIFIED" if not (cat and dur) else "SHORT / DIFFERENT TRACK")))
    if vip is not None:
        print("   member-only track: only_vip_playable=%s only_vip_download=%s need_vip=%s"
              % (info.get("only_vip_playable"), info.get("only_vip_download"),
                 info.get("need_vip")))
        if ours and vip:
            print("   -> the logged-in account DOES receive the full song for this member track")
    if not ours and cat and dur:
        print("  refusing to certify: the media decodes to %.1f s but the track is %.1f s"
              % (dur, cat))

    if not a.no_verify:
        try:
            v = verify(chosen["url"], chosen["declared_size"])
            result["verified"] = v
            print("verified: %s" % json.dumps(v, ensure_ascii=False))
        except Exception as e:
            result["verify_error"] = str(e)[:120]
            print("verify failed: %s" % e)

    if a.download:
        # Save under the tier the client CONFIRMED, so the filename cannot overstate what was
        # actually served. A file whose duration did not match is tagged, never presented as good.
        tag = chosen["quality"]
        if not chosen.get("confirmed"):
            tag += "_unconfirmed"
        if not ours:
            tag += "_wrongtrack"
        outdir = os.environ.get("SODA_OUT_DIR") or "soda_out"
        os.makedirs(outdir, exist_ok=True)
        ext = ".flac" if (chosen.get("codec_type") or "").lower() == "flac" else ".m4a"
        fn = os.path.join(outdir, "fetch_%s_%s%s" % (a.track, tag, ext))
        ctx = ssl.create_default_context()
        rq = urllib.request.Request(chosen["url"], headers={"User-Agent": UA})
        with urllib.request.urlopen(rq, timeout=900, context=ctx) as r:
            data = r.read()
        open(fn, "wb").write(data)
        result["file"] = fn
        result["bytes"] = len(data)
        result["sha256"] = hashlib.sha256(data).hexdigest()
        print("downloaded: %s (%d bytes)" % (fn, len(data)))

    if a.json:
        json.dump(result, open(a.json, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
        print("json -> %s" % a.json)

    # Exit codes let a caller branch without parsing output:
    #   0  a tier belonging to this track was delivered
    #   2  nothing was delivered (no confirmed tier)
    #   3  a tier was delivered but its content is NOT this track
    return 3 if (chosen and not ours and cat and dur) else 0


if __name__ == "__main__":
    sys.exit(main())
