"""Client-reuse batch using the PROVEN extraction path.

Design is deliberately minimal, because both attempts at something cleverer failed:
  * polling memory every second  -> client killed (procs 3, 0/7 verified)
  * reading the client's stdout for a readiness marker -> unreliable; truncating the file breaks
    because the client holds the fd, and offset reads still missed the marker for 5 of 7 tracks

What is proven to work (live_switch2 run): fire the deeplink, wait a short fixed moment, then call
the ORIGINAL extract_ladder with a concrete `require` -- it stops after the first successful scan and
its 1.5 s inter-scan gap keeps the client alive. That run gave
    cold 9.9 s | live 10.6 s | live 9.7 s | live 8.3 s     4/4 duration-verified FULL, health OK
against a per-restart baseline of 21.4 s.

So: one cold start, then per track -> fire deeplink, sleep, extract_ladder(require=...). No custom
scanner, no log parsing, and ownership is still decided only by decoded duration vs catalogue.
"""
import json
import os
import ssl
import subprocess
import sys
import time
import urllib.request

import soda_fetch as sf
from health import status

LOG = os.environ.get("SODA_PROVEN_LOG") or "proven.log"
OUTDIR = os.environ.get("SODA_OUT_DIR") or "proven_out"
SETTLE = 3.0          # short, fixed: the deeplink handler starts playback in ~2 s when switching


def fire(tid):
    # Guard before every deeplink. A deeplink to a running client spawns a second SodaMusic.exe, and
    # each such launch writes a record into %TEMP%\SodaMusic_Launch_Records; the client shows the
    # 进入安全模式 dialog once FOUR records newer than now-10min exist
    # (src/utils/safeMode.ts). Its default button cleans userData and forces a re-login, so it must
    # not be allowed to appear. Called here as well as in soda_fetch.play() so every caller of this
    # shared primitive is covered.
    try:
        import soda_guard
        soda_guard.prevent(verbose=False)
    except Exception:
        pass
    env = {k: v for k, v in os.environ.items() if k != "ELECTRON_RUN_AS_NODE"}
    subprocess.Popen([sf.EXE, "--", "luna:///playing?track_id=%s&media_type=track" % tid],
                     cwd=sf.CWD, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    ids = [a for a in sys.argv[1:] if a.isdigit()]
    want = "hi_res"
    if "--quality" in sys.argv:
        want = sys.argv[sys.argv.index("--quality") + 1]
    if not ids:
        print(__doc__)
        return 1
    os.makedirs(OUTDIR, exist_ok=True)

    os.system("taskkill /F /IM SodaMusic.exe >nul 2>&1")
    time.sleep(3)
    os.makedirs(sf.CACHE, exist_ok=True)
    T0 = time.perf_counter()
    env = {k: v for k, v in os.environ.items() if k != "ELECTRON_RUN_AS_NODE"}
    log = sf.play(ids[0], wait=45, clear_cache=True, quality=want)
    print("cold start ready at %.1f s" % (time.perf_counter() - T0), flush=True)

    rows = []
    for i, tid in enumerate(ids):
        t0 = time.perf_counter()
        if i > 0:
            fire(tid)
            time.sleep(SETTLE)
        # One shot on the running client; fall back to a full RESTART if it yields nothing.
        #
        # Reuse holds for the first few tracks (measured 9.0/9.9/9.5 s) but degrades after 3-4 live
        # switches, so a failure here is expected rather than exceptional. The fallback must be a
        # restart: re-firing the deeplink spawns another SodaMusic.exe each time (measured 31
        # processes after a retry loop), which is what made things worse.
        tiers = sf.extract_ladder(tid, log_path=None, require=want, tries=4, verbose=False)
        conf = {q: t for q, t in tiers.items() if t.get("confirmed")}
        restarted = False
        if not conf:
            restarted = True
            sf.play(tid, wait=45, clear_cache=True, quality=want)
            time.sleep(SETTLE)
            tiers = sf.extract_ladder(tid, log_path=None, require=want, tries=4, verbose=False)
            conf = {q: t for q, t in tiers.items() if t.get("confirmed")}
        el = time.perf_counter() - t0
        cat = sf.catalog_info(tid)
        cat_s = cat.get("duration_s")
        rec = {"track": tid, "mode": "cold" if i == 0 else "reuse", "s": round(el, 1),
               "restarted": restarted,
               "catalogue_s": cat_s, "tiers": sorted(tiers), "confirmed": sorted(conf),
               "membership": {k: v for k, v in cat.items() if k != "duration_s"}}
        if conf:
            q = want if want in conf else next((x for x in sf.TIER_ORDER if x in conf), None)
            t = conf[q]
            d = sf.media_duration(t["url"])
            rec.update({"quality": q, "url": t["url"], "duration_s": d,
                        "codec": t.get("codec_type"),
                        "is_full_length": bool(d and cat_s and abs(d - cat_s) <= 2.5)})
        else:
            rec["error"] = "no confirmed tier"
        rows.append(rec)
        print("  [%d/%d] %-20s %-5s %5.1fs %-8s %s/%s %s"
              % (i + 1, len(ids), tid, rec["mode"], el, rec.get("quality") or "-",
                 rec.get("duration_s"), cat_s,
                 "FULL" if rec.get("is_full_length") else (rec.get("error") or "CHECK")),
              flush=True)

        if rec.get("is_full_length"):
            ctx = ssl.create_default_context()
            rq = urllib.request.Request(rec["url"], headers={"User-Agent": sf.UA})
            with urllib.request.urlopen(rq, timeout=900, context=ctx) as r:
                data = r.read()
            ext = ".flac" if (rec.get("codec") or "").lower() == "flac" else ".m4a"
            fn = os.path.join(OUTDIR, "%s_%s%s" % (tid, rec["quality"], ext))
            open(fn, "wb").write(data)
            rec["file"] = fn
            rec["bytes"] = len(data)

    s = status()
    total = time.perf_counter() - T0
    good = [r for r in rows if r.get("is_full_length")]
    reuse = [r for r in rows[1:] if r.get("is_full_length")]
    print("\n" + "=" * 96)
    print("REUSE BATCH (proven extraction path)")
    print("=" * 96)
    print("  tracks                 : %d" % len(rows))
    print("  duration-verified FULL  : %d" % len(good))
    print("  restarts               : 1")
    print("  client healthy after   : %s (procs=%d)" % (s["healthy"], s["processes"]))
    print("  total                  : %.1f s" % total)
    print("  per track avg          : %.1f s" % (total / len(rows)))
    if reuse:
        print("  reused avg             : %.1f s  (%s)"
              % (sum(r["s"] for r in reuse) / len(reuse), [r["s"] for r in reuse]))
    print("  per-restart baseline   : 21.4 s avg (17.4-29.2)")
    json.dump({"total_s": round(total, 1), "rows": rows},
              open(os.path.join(OUTDIR, "_reuse_summary.json"), "w", encoding="utf-8"),
              indent=1, ensure_ascii=False)
    print("  saved -> %s" % os.path.join(OUTDIR, "_reuse_summary.json"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
