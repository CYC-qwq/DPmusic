"""Silence the client's audio in Windows, targeted at this app only.

Requirement: after the daemon has a link, the client should stop making noise. Measured facts that
shape this module:

  * the client registers its own Windows media session, named 汽水音乐
    (`APPHEX=e6b1bde6b0b4e99fb3e4b990`), so it can be controlled by app instead of globally.
    A global media key would be routed to whichever session is "current" -- possibly the user's other
    player -- so it is deliberately NOT used.
  * pausing preserves the ladder completely: after a pause all five urls were byte-identical at +5 s
    and +10 s, and extraction kept returning tiers.
  * pausing does not kill the client: 30 s of polling showed `Paused` with the same 7 pids.
  * the next fetch works from a paused client: a deeplink fired at a paused client produced a full
    ladder again.

Two actions are offered:
  pause()  -- stops playback. This is what was asked for.
  mute()   -- silences only the client's audio session, leaving playback state untouched. Kept as a
              fallback for the case where pausing ever proves disruptive, since it cannot affect the
              ladder at all.

The PowerShell is a .ps1 invoked by file, not an inline -Command. That is not cosmetic: a command
line carrying non-ASCII (the session name is Chinese) was corrupted on the way in, so the name never
matched and the action silently did nothing. Passing the match as hex and the output as hex removes
every encoding step that could ruin it.
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PS1 = os.path.join(HERE, "smtc.ps1")
SODA_HEX = "e6b1bde6b0b4"          # UTF-8 of 汽水
TIMEOUT = 90


def _ps(mode, match=SODA_HEX):
    if not os.path.exists(PS1):
        return None, ["smtc.ps1 not found next to soda_pause.py"]
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                            "-File", PS1, "-Mode", mode, "-MatchHex", match],
                           capture_output=True, text=True, encoding="utf-8", errors="replace",
                           timeout=TIMEOUT)
    except subprocess.TimeoutExpired:
        return None, ["smtc call timed out"]
    return ((r.stdout or "") + (r.stderr or "")).strip().splitlines(), None


def parse(lines):
    """-> (found, status, title_hex, action_ok)"""
    found = False
    status = title_hex = None
    action_ok = None
    for l in lines or []:
        l = l.strip()
        if l.startswith("SESS"):
            found = True
            for tok in l.split():
                if tok.startswith("STATUS="):
                    status = tok[7:]
                elif tok.startswith("TITLEHEX="):
                    title_hex = tok[9:]
        elif l.startswith("ACTION="):
            for tok in l.split():
                if tok.startswith("OK="):
                    action_ok = tok[3:] == "True"
    return found, status, title_hex, action_ok


def _state_once(match=SODA_HEX):
    lines, err = _ps("status", match)
    if err:
        return None, None, None
    found, st, th, _ = parse(lines)
    return found, st, th


def _await_status(want, timeout=6.0, poll=0.3):
    """Poll until the session reports `want`. Returns (reached, last_status).

    Needed because the status is not updated synchronously with the control call: the action returns
    OK while a single immediate re-read still shows the PREVIOUS state (measured -- pause() reported
    `status=Playing` and the very next independent read showed `Paused`, and the same on resume). The
    action was in fact applied; only the verification was racing.
    """
    deadline = time.time() + timeout
    last = None
    while True:
        found, st, th = _state_once()
        last = st
        if found and st == want:
            return True, st
        if time.time() >= deadline:
            return False, last
        time.sleep(poll)


def _state_retry(match=SODA_HEX, attempts=4, gap=0.8):
    """Read the session state, retrying while no session is reported.

    A single read can legitimately come back empty: the client's media session is torn down and
    re-registered around playback transitions (measured -- a probe right after a track change saw
    COUNT=0, and a probe right after a relay fetch saw `status=None`, while the reads immediately
    before and after both saw the session). Retrying turns a transient gap into a real reading
    instead of a false "not found".
    """
    last = (False, None, None)
    for i in range(max(1, attempts)):
        last = _state_once(match)
        if last[0]:
            return last
        if i + 1 < attempts:
            time.sleep(gap)
    return last


def status():
    """-> (found, playback_status, title_hex). Read-only: never changes playback."""
    return _state_retry()


def pause(verbose=False):
    """Stop the client's playback. Returns True once the status actually reads Paused.

    Measured to be safe for extraction: the ladder is untouched and the process keeps running.
    """
    lines, err = _ps("pause")
    if err:
        if verbose:
            print("  pause: %s" % err)
        return False
    found, _, _, ok = parse(lines)
    if not found:
        if verbose:
            print("  pause: no client media session")
        return False
    reached, st = _await_status("Paused")
    if verbose:
        print("  pause: ok=%s -> status=%s" % (ok, st))
    return reached


def resume(verbose=False):
    lines, err = _ps("play")
    if err:
        if verbose:
            print("  resume: %s" % err)
        return False
    found, _, _, ok = parse(lines)
    if not found:
        if verbose:
            print("  resume: no client media session")
        return False
    reached, st = _await_status("Playing")
    if verbose:
        print("  resume: ok=%s -> status=%s" % (ok, st))
    return reached


def toggle(verbose=False):
    lines, err = _ps("toggle")
    if err:
        return False
    found, st, th, ok = parse(lines)
    return bool(found and ok)


if __name__ == "__main__":
    cmd = (sys.argv[1] if len(sys.argv) > 1 else "status").lower()
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    if cmd == "status":
        f, st, th = status()
        print("found=%s status=%s title_hex=%s" % (f, st, th))
    elif cmd == "pause":
        print("paused" if pause(verbose=True) else "pause failed")
    elif cmd == "resume":
        print("resumed" if resume(verbose=True) else "resume failed")
    elif cmd == "toggle":
        print("toggled" if toggle(verbose=True) else "toggle failed")
    else:
        print("usage: soda_pause.py [status|pause|resume|toggle]")
