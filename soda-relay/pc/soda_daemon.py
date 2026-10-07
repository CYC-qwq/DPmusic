"""Soda Music home-side daemon: serves DPmusic track links over a WebSocket to the relay.

Responsibilities
  1. own the Soda Music client's lifecycle (start, switch tracks, restart when it degrades)
  2. answer `play` requests with a duration-verified CDN link, using a 12 h cache
  3. serve `audio` requests by proxying the SAME byte Range, for devices that cannot reach the CDN
  4. authenticate every message with a shared-secret HMAC

Design notes, each grounded in a measurement from this project:

  * SERIAL EXECUTION. The client can only play one track at a time, so every extraction goes
    through a single-slot queue. Identical requests are coalesced so a burst of devices asking for
    the same song costs one extraction.

  * PROACTIVE RESTART EVERY 3 REUSES. Reusing the running client via deeplink is ~2x faster than a
    restart (8-10 s vs 16-21 s per track) but the client stops resolving tiers after 3-4
    consecutive switches. So: reuse up to 3 times, then restart before the next one.

  * VERIFY BY DURATION, NEVER BY POSITION. The client preloads following tracks, so several ladders
    coexist in memory. A tier only counts when its decoded duration matches the catalogue duration
    of the requested id -- byte hashes and the client's own request log both misled earlier.

  * NO DEBUG FLAGS. `--inspect-brk` / `--remote-debugging-port` crash the client and trip a sticky
    protection dialog; a probe confirmed CDP is unusable on this build.

  * LOSSLESS IS NOT OFFERED. It is only present in play_info when the client itself requests it, and
    the client cannot be held on that tier (hardcoded DEFAULT_QUALITY='higher' is absent from the
    server's quality list, so commerce resets it every launch). See PROTOCOL.md section 6.

Run:
    set SODA_SHARED_SECRET=<secret>
    python soda_daemon.py --relay wss://your-host/agent --node home-pc

Requires the same Python environment as soda_fetch.py (it imports that module directly).
"""
import argparse
import asyncio
import concurrent.futures
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
# Local modules live beside this file in ./sodalib, so the package runs unmodified from a
# fresh clone. No install step and no path outside the tree is required.
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "sodalib"))

import soda_fetch as sf                                    # noqa: E402
from health import status as client_status                 # noqa: E402
from soda_reuse_proven import fire, SETTLE                 # noqa: E402
import soda_guard                                          # noqa: E402

try:
    import websockets
except ImportError:                                        # pragma: no cover
    websockets = None

VER = 1
CACHE_TTL_S = 12 * 3600          # measured: links stayed valid past 18.6 h; keep a margin
MAX_REUSE_BEFORE_RESTART = 3     # measured: the client degrades after 3-4 consecutive switches
EXTRACT_TIMEOUT_S = 120
MAX_QUEUE = 32
AUDIO_TIMEOUT_S = 300
LOCK_PORT = 47653                # loopback port used as the single-instance lock


def log(*a):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), " ".join(str(x) for x in a)), flush=True)


def sign(secret, kind, ts):
    return hmac.new(secret.encode(), ("%s.%s" % (kind, ts)).encode(), hashlib.sha256).hexdigest()


def verify(secret, kind, ts, sig, window=300):
    try:
        ts = int(ts)
    except (TypeError, ValueError):
        return False, "bad ts"
    if abs(time.time() - ts) > window:
        return False, "ts out of window"
    if not hmac.compare_digest(sign(secret, kind, ts), str(sig or "")):
        return False, "bad sig"
    return True, ""


class SodaService:
    """Owns the client and the cache; all blocking work runs in one worker thread."""

    def __init__(self, allow_restart=True):
        self.cache = {}                 # (track_id, quality) -> {url, ..., stored_at}
        self.reuse_count = 0
        self.allow_restart = allow_restart
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        self.pending = {}               # key -> list of futures (coalescing)
        self.lock = asyncio.Lock() if False else None   # created lazily inside the loop

    # ---------- client lifecycle ----------

    def _healthy(self):
        try:
            return client_status()["healthy"]
        except Exception:
            return False

    def _cold_start(self, track_id, quality):
        """Full restart: kill, clear logs/cache, launch, then extract. Measured 4-11 s to be ready."""
        log("cold start for %s" % track_id)
        # Preventive step, before every launch. src/utils/safeMode.ts shows the client inspects
        # %TEMP%\SodaMusic_Launch_Records and shows the 进入安全模式 dialog once FOUR records newer
        # than now-10min exist. That handler cleans userData and forces a re-login, and defaultId is
        # 0, so it must never be allowed to appear. Our batch restarts plus the deeplink
        # second-instance launches cross the threshold routinely.
        soda_guard.prevent()
        sf.play(track_id, wait=45, clear_cache=True, quality=quality)
        self.reuse_count = 0

    def _maybe_proactive_restart(self, track_id, quality):
        """Restart BEFORE the client degrades, rather than waiting for a failure."""
        if self.reuse_count >= MAX_REUSE_BEFORE_RESTART:
            log("reuse count %d reached limit; proactive restart" % self.reuse_count)
            self._cold_start(track_id, quality)
            return True
        return False

    def _switch(self, track_id):
        # The deeplink spawns a second SodaMusic.exe, which writes its own launch record, so the
        # counter has to be kept low here too -- not only on a cold start.
        soda_guard.prevent()
        fire(track_id)
        time.sleep(SETTLE)

    # ---------- extraction (blocking, runs in the worker thread) ----------

    def _extract_once(self, track_id, quality):
        """One attempt on the current client. Returns (tier, info) or (None, None)."""
        tiers = sf.extract_ladder(track_id, log_path=None, require=quality, tries=4, verbose=False)
        conf = {q: t for q, t in tiers.items() if t.get("confirmed")}
        if not conf:
            return None, None
        q = quality if quality in conf else next((x for x in sf.TIER_ORDER if x in conf), None)
        if not q:
            return None, None
        t = conf[q]
        cat = sf.catalog_duration(track_id)
        d = sf.media_duration(t["url"])
        if not (d and cat and abs(d - cat) <= 2.5):
            return None, None          # wrong track or a preview clip: never deliver this

        info = {"url": t["url"], "quality": q, "codec": t.get("codec_type") or
                ("flac" if q == "lossless" else None),
                "duration_s": d, "catalogue_s": cat, "is_full_length": True}

        # ---- CENC key material (protocol requirement A) --------------------------------
        # The audio is CENC AES-CTR (stsd=enca + senc); without the key a client gets silent
        # buffering. The client's own tier object carries it as `encrypt_info.spade_a`, NOT as
        # `play_auth` -- verified by dumping memory during playback, and confirmed by decrypting
        # real files: all six tiers (medium/higher/highest/hi_res/spatial/lossless) decode with
        # ffmpeg rc=0 and zero errors once the SAME object's key is applied.
        #
        # The key MUST come from the same tier object as the url: every tier has its own `kid`, and
        # a mismatched key decrypts to noise. `encrypt_info` is therefore carried through grouping
        # attached to its url, never looked up separately.
        enc = t.get("encrypt_info") or {}
        spade = enc.get("spade_a") or ""
        info["play_auth"] = spade                       # raw blob, name required by the protocol
        info["encrypted"] = bool(enc.get("encrypt") and spade)
        info["encryption"] = enc.get("encryption_method") or (
            "cenc-aes-ctr" if spade else None)
        info["kid"] = enc.get("kid")
        if info["encrypted"] and not info["encryption"]:
            info["encryption"] = "cenc-aes-ctr"
        return q, info

    def fetch(self, track_id, quality, allow_restart=True):
        """Blocking. Serves from cache, otherwise drives the client. Runs in the worker thread."""
        key = (track_id, quality)
        hit = self.cache.get(key)
        if hit and time.time() - hit["stored_at"] < CACHE_TTL_S:
            log("cache hit %s %s" % (track_id, quality))
            return {"ok": True, "cache": "hit", **{k: v for k, v in hit.items() if k != "stored_at"}}

        if not self._healthy():
            log("client unhealthy; cold start")
            self._cold_start(track_id, quality)
        elif self._maybe_proactive_restart(track_id, quality):
            pass
        elif self.reuse_count > 0:
            self._switch(track_id)
        else:
            self._switch(track_id)

        q, info = self._extract_once(track_id, quality)
        if info:
            self.reuse_count += 1
        elif allow_restart:
            log("extraction failed; restart and retry once")
            self._cold_start(track_id, quality)
            q, info = self._extract_once(track_id, quality)
            if info:
                self.reuse_count = 1

        if not info:
            return {"ok": False, "code": "no_tier",
                    "message": "client resolved no duration-verified tier for this track"}
        info["stored_at"] = time.time()
        self.cache[key] = info
        # Silence the client now that the link is in hand, so fetching tracks does not play music out
        # loud on the home PC.
        #
        # Safe to do HERE and not earlier: measured, a pause leaves the ladder fully intact (all five
        # urls byte-identical at +5 s and +10 s), does not kill the client (30 s of polling stayed
        # `Paused` with the same 7 pids), and the next fetch still works from a paused client (a
        # deeplink fired at a paused client produced a full ladder). Doing it before extraction would
        # be gambling with the very thing being read.
        self._quiet_client()
        return {"ok": True, "cache": "miss", **{k: v for k, v in info.items() if k != "stored_at"}}

    def _quiet_client(self):
        """Pause the client's own media session. Never raises.

        Failure is non-fatal by design: silence is a nicety, the link is the product. If the media
        session is unavailable the user just keeps hearing the track, which is the old behaviour.
        """
        if os.environ.get("SODA_NO_PAUSE"):
            return
        try:
            import soda_pause
            if soda_pause.pause():
                log("client paused after fetch")
            else:
                found, st, _ = soda_pause.status()
                log("client pause skipped (session_found=%s status=%s)" % (found, st))
        except Exception as e:
            log("client pause failed: %s" % str(e)[:80])

    def proxy_audio(self, track_id, quality, rng):
        """Blocking. Relays the SAME Range from the CDN, so seeking still works on the device."""
        key = (track_id, quality)
        hit = self.cache.get(key)
        if not hit or time.time() - hit["stored_at"] >= CACHE_TTL_S:
            r = self.fetch(track_id, quality)
            if not r.get("ok"):
                return None, None
            hit = self.cache[key]
        headers = {"User-Agent": sf.UA}
        if rng:
            headers["Range"] = rng
        req = urllib.request.Request(hit["url"], headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=AUDIO_TIMEOUT_S) as resp:
                return resp, resp.headers
        except Exception as e:
            log("proxy fetch failed: %s" % str(e)[:80])
            return None, None


async def handle(ws, svc, secret, node):
    loop = asyncio.get_running_loop()
    sem = asyncio.Semaphore(MAX_QUEUE)

    async def run_blocking(fn, *a, **kw):
        return await loop.run_in_executor(svc.pool, lambda: fn(*a, **kw))

    async def send(obj):
        await ws.send(json.dumps(obj, ensure_ascii=False))

    log("connected; sending hello")
    ts = int(time.time())
    await send({"type": "hello", "ver": VER, "node": node, "ts": ts,
                "sig": sign(secret, "hello", ts), "capabilities":
                {"qualities": ["lossless", "hi_res", "spatial", "highest", "higher", "medium"],
                 "lossless": True, "proxy_audio": True, "cache_ttl_s": CACHE_TTL_S}})

    async for raw in ws:
        if isinstance(raw, (bytes, bytearray)):
            continue
        try:
            msg = json.loads(raw)
        except Exception:
            continue
        t = msg.get("type")

        if t == "ping":
            await send({"type": "pong", "ts": int(time.time())})
            continue

        if t != "play":
            log("unknown message type: %r" % t)
            continue

        ok, why = verify(secret, "play", msg.get("ts"), msg.get("sig"))
        if not ok:
            log("rejecting play: %s" % why)
            await send({"type": "result", "id": msg.get("id"), "ok": False,
                        "code": "bad_request", "message": why})
            continue

        rid = msg.get("id")
        track_id = str(msg.get("track_id") or "")
        quality = msg.get("quality") or "hi_res"
        allow_restart = bool(msg.get("allow_restart", True))
        if not track_id.isdigit() or quality not in sf.TIER_ORDER:
            await send({"type": "result", "id": rid, "ok": False, "code": "bad_request",
                        "message": "track_id must be digits; quality must be one of "
                                   "lossless/hi_res/spatial/highest/higher/medium"})
            continue

        async with sem:
            t0 = time.perf_counter()
            res = await run_blocking(svc.fetch, track_id, quality, allow_restart)
            # The relay dispatches on `type`; omitting it makes the reply unparseable and the
            # device request hangs until its timeout (observed as status=503 after 150 s while the
            # daemon had actually succeeded in 14.9 s).
            res["type"] = "result"
            res["id"] = rid
            res["took_ms"] = int((time.perf_counter() - t0) * 1000)
            res["expires_hint_s"] = CACHE_TTL_S
            await send(res)
            log("play %s -> ok=%s cache=%s took=%dms"
                % (track_id, res.get("ok"), res.get("cache"), res["took_ms"]))


async def main_async(args):
    if websockets is None:
        log("the 'websockets' package is required (pip install websockets)")
        return 2
    svc = SodaService()
    secret = os.environ.get("SODA_SHARED_SECRET", "")
    if not secret:
        log("SODA_SHARED_SECRET is not set; refusing to start")
        return 2

    # Belt and braces: prevent() is called before each launch, and this loop handles the case where
    # a dialog is on screen anyway (e.g. it was triggered by something outside this process). It
    # clicks 忽略 by the button's own handle and never sends Enter, because defaultId is 0 --
    # "进入安全模式" -- which cleans userData and forces a re-login.
    guard = soda_guard.Guard(interval=1.0).start()
    log("soda_guard started: watching %s" % soda_guard.LAUNCH_DIR)

    backoff = 2
    while True:
        try:
            log("connecting to %s as %s" % (args.relay, args.node))
            async with websockets.connect(args.relay, ping_interval=20, ping_timeout=20,
                                          max_size=8 * 1024 * 1024) as ws:
                backoff = 2
                await handle(ws, svc, secret, args.node)
        except Exception as e:
            log("connection lost (%s); retrying in %ds" % (str(e)[:90], backoff))
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60)


def acquire_single_instance_lock():
    """Refuse to run if another daemon already holds the lock.

    Why this exists: several `soda_daemon.py` processes ended up running at once during testing
    (the launcher loops, and every manual start added one). They all talk to the relay, but the
    relay keeps only the newest agent socket in a single slot -- so an OLD process can answer a
    request while a NEWER one is idle, and the old one may be running different code. That produced
    a confusing "the daemon returns old error text" symptom.

    Implemented by binding a loopback port: the OS guarantees only one owner. No file is left behind
    if the process dies, unlike a pid file.
    """
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("127.0.0.1", LOCK_PORT))
        s.listen(1)
        return s
    except OSError:
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--relay", required=True, help="wss:// URL of the relay's agent endpoint")
    ap.add_argument("--node", default="home-pc", help="node name reported in the hello")
    # Reading the secrets file here keeps the launcher trivial. Parsing JSON in a .bat with
    # `for /f` would drag in the surrounding quotes and commas, so the batch file only has to
    # pass this one argument.
    ap.add_argument("--secrets", help="path to relay_secrets.json; fills the env vars from it")
    ap.add_argument("--force", action="store_true",
                    help="start even if another daemon is running (not recommended)")
    a = ap.parse_args()

    lock = None
    if not a.force:
        lock = acquire_single_instance_lock()
        if lock is None:
            print("[%s] another soda_daemon is already running (port %d is taken); exiting."
                  % (time.strftime("%H:%M:%S"), LOCK_PORT), flush=True)
            return 0

    if a.secrets:
        try:
            with open(a.secrets, encoding="utf-8") as f:
                sec = json.load(f)
            os.environ.setdefault("SODA_SHARED_SECRET", sec.get("SODA_SHARED_SECRET", ""))
            os.environ.setdefault("DEVICE_SECRET", sec.get("DEVICE_SECRET", ""))
            print("[%s] loaded secrets from %s" % (time.strftime("%H:%M:%S"), a.secrets),
                  flush=True)
        except Exception as e:
            print("[%s] could not read %s: %s" % (time.strftime("%H:%M:%S"), a.secrets, e),
                  flush=True)

    try:
        return asyncio.run(main_async(a))
    except KeyboardInterrupt:
        return 0
    finally:
        if lock:
            try:
                lock.close()
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(main())
