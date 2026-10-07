"""Keep the "异常诊断 / 进入安全模式" dialog from ever appearing, and dismiss it if it does.

Mechanism, read directly from the shipped source `src/utils/safeMode.ts` (extracted from the
`main.js.map` inside `app.asar`):

    const LAUNCH_RECORD_PATH = path.resolve(TEMP_PATH, 'SodaMusic_Launch_Records')
    const CURRENT_LAUNCH_RECORD = Date.now()
    const EARLIEST_LAUNCH_RECORD = Date.now() - (10 * 60 * 1000)      // 10-minute window

    export function checkSafeMode() {
      if (IS_DEV) return
      fs.mkdirSync(LAUNCH_RECORD_PATH, { recursive: true })
      const records = fs.readdirSync(LAUNCH_RECORD_PATH)
      const launchCount = records.filter(t => Number(t) > EARLIEST_LAUNCH_RECORD).length
      if (launchCount >= 4) {
        const result = dialog.showMessageBoxSync(null, {
          title: '异常诊断',
          message: '检测到您多次尝试启动汽水音乐，…',
          buttons: ['进入安全模式', '忽略'],
          defaultId: 0,
        })
        for (const record of records) fs.unlinkSync(...)   // clears the records
        if (result === 0) {                                // 进入安全模式
          …kill other SodaMusic.exe, cleanDirectorySync(userData), app.relaunch(), app.exit()
        }
      }
      if (records.length > 10) { …delete them… }
      fs.writeFileSync(path.resolve(LAUNCH_RECORD_PATH, String(CURRENT_LAUNCH_RECORD)), '')
    }

Three consequences drive this module:

  1. The state lives in `%TEMP%\\SodaMusic_Launch_Records` as empty files NAMED with a millisecond
     timestamp -- NOT in crash_log.json (which is a different, unrelated mechanism). Only names newer
     than `now - 10 min` are counted.
  2. The threshold is FOUR launches inside 10 minutes. Our batch pipeline restarts the client, and
     every `lume:///` deeplink spawns another SodaMusic.exe, so we cross it routinely.
  3. `defaultId: 0` is "进入安全模式", which cleans the user data directory and FORCES A NEW LOGIN.
     So a stray Enter that lands on the dialog is destructive -- dismissal must click "忽略"
     explicitly and must never send a RETURN key.

Prevention is therefore the primary control (keep the counted records below 4), with dismissal as a
fallback for the case where the dialog is already on screen.
"""
import ctypes
import ctypes.wintypes as wt
import glob
import os
import subprocess
import sys
import tempfile
import time

LAUNCH_DIR = os.path.join(tempfile.gettempdir(), "SodaMusic_Launch_Records")
WINDOW_MS = 10 * 60 * 1000          # EARLIEST_LAUNCH_RECORD = Date.now() - 10 min
THRESHOLD = 4                       # launchCount >= 4 shows the dialog
KEEP_FILES = 10                     # the app deletes records when records.length > 10

DIALOG_TITLE = "异常诊断"
DIALOG_BODY_MARK = "多次尝试启动"
BTN_IGNORE = "忽略"


def _log(msg):
    print("[guard %s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# --------------------------------------------------------------------------- prevention

def counted_records(now_ms=None):
    """Records the app would count: numeric names newer than now - 10 minutes."""
    now_ms = now_ms or int(time.time() * 1000)
    cutoff = now_ms - WINDOW_MS
    out = []
    try:
        for name in os.listdir(LAUNCH_DIR):
            try:
                v = int(name)
            except ValueError:
                continue
            if v > cutoff:
                out.append((v, name))
    except FileNotFoundError:
        return []
    return sorted(out)


def prevent(keep=THRESHOLD - 1, verbose=True):
    """Bring the counted records below the threshold.

    Called immediately BEFORE launching the client, so the launch about to happen is the 4th at
    worst. Returns how many records were removed.
    """
    os.makedirs(LAUNCH_DIR, exist_ok=True)
    recs = counted_records()
    removed = 0
    if len(recs) > keep:
        # delete the oldest ones first: the newest are the ones we are about to add to
        for _, name in recs[: len(recs) - keep]:
            try:
                os.remove(os.path.join(LAUNCH_DIR, name))
                removed += 1
            except OSError:
                pass
    # also honour the app's own records.length > 10 cleanup, on the whole directory
    try:
        all_names = sorted(os.listdir(LAUNCH_DIR))
        if len(all_names) > KEEP_FILES:
            for name in all_names[: len(all_names) - KEEP_FILES]:
                try:
                    os.remove(os.path.join(LAUNCH_DIR, name))
                    removed += 1
                except OSError:
                    pass
    except FileNotFoundError:
        pass
    if verbose and removed:
        _log("prevented: removed %d launch record(s); counted now %d (<%d)"
             % (removed, len(counted_records()), THRESHOLD))
    return removed


def clear_all(verbose=True):
    """Delete every record. Used when the dialog has already fired (the app does this itself)."""
    n = 0
    try:
        for name in os.listdir(LAUNCH_DIR):
            try:
                os.remove(os.path.join(LAUNCH_DIR, name))
                n += 1
            except OSError:
                pass
    except FileNotFoundError:
        pass
    if verbose and n:
        _log("cleared %d launch record(s)" % n)
    return n


# --------------------------------------------------------------------------- dismissal

user32 = ctypes.WinDLL("user32", use_last_error=True)
EnumWindowsProc = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)


def _title(hwnd):
    n = user32.GetWindowTextLengthW(hwnd)
    if n <= 0:
        return ""
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def _class(hwnd):
    buf = ctypes.create_unicode_buffer(256)
    user32.GetClassNameW(hwnd, buf, 256)
    return buf.value


def _child_texts(hwnd):
    """Text of the window and its children, for identifying the buttons."""
    found = []

    def cb(h, _):
        t = _title(h)
        if t:
            found.append((h, t))
        return True

    user32.EnumChildWindows(hwnd, EnumWindowsProc(cb), 0)
    return found


def find_dialog():
    """Return the dialog's hwnd, or None. Identified by title AND body text, not by title alone."""
    hits = []

    def cb(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        t = _title(hwnd)
        if t.strip() == DIALOG_TITLE:
            kids = _child_texts(hwnd)
            texts = " ".join(x[1] for x in kids)
            if DIALOG_BODY_MARK in texts or any(BTN_IGNORE == x[1] for x in kids):
                hits.append((hwnd, kids))
        return True

    user32.EnumWindows(EnumWindowsProc(cb), 0)
    return hits[0] if hits else None


def _click_at(x, y):
    """Synthesise a click at absolute screen coordinates."""
    user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))   # PER_MONITOR_AWARE_V2
    user32.SetCursorPos(int(x), int(y))
    MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
    user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
    time.sleep(0.05)
    user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)


def _button_center(hwnd):
    r = wt.RECT()
    if not user32.GetWindowRect(hwnd, ctypes.byref(r)):
        return None
    return ((r.left + r.right) // 2, (r.top + r.bottom) // 2)


def dismiss(reason="unknown"):
    """Click "忽略" on the dialog. NEVER sends Enter: defaultId is 0, i.e. 进入安全模式, which wipes
    the user data directory and forces a fresh login.

    Returns True if a dialog was found and handled.
    """
    try:
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except Exception:
        pass

    found = find_dialog()
    if not found:
        return False
    hwnd, kids = found
    _log("dialog detected (%s): hwnd=%s title=%r" % (reason, hwnd, _title(hwnd)))
    for h, t in kids:
        if t.strip():
            _log("   child %r" % t[:70])

    # 1. the real button control, if this is a standard task dialog
    target = None
    for h, t in kids:
        if t.strip() == BTN_IGNORE:
            target = h
            break
    if target:
        user32.SendMessageW(target, 0x00F5, 0, 0)          # BM_CLICK
        _log("clicked the 忽略 button by handle")
        time.sleep(0.4)
        if find_dialog() is None:
            clear_all()
            return True
        _log("still present after BM_CLICK; retrying by coordinates")

    # 2. click by coordinates on the next-to-last button, which is 忽略
    if len(kids) >= 2:
        btns = [h for h, t in kids if t.strip() in (BTN_IGNORE, "进入安全模式")]
        if not btns:
            btns = [h for h, _ in kids][-2:]
        pick = None
        for h, t in kids:
            if t.strip() == BTN_IGNORE:
                pick = h
        pick = pick or btns[-1]
        c = _button_center(pick)
        if c:
            _log("clicking 忽略 at %s" % (c,))
            _click_at(*c)
            time.sleep(0.4)
            if find_dialog() is None:
                clear_all()
                return True

    clear_all()                                            # the app clears records on dialog close
    return find_dialog() is None


def state():
    """Diagnostic snapshot."""
    recs = counted_records()
    return {"dir": LAUNCH_DIR, "dir_exists": os.path.isdir(LAUNCH_DIR),
            "counted": len(recs), "threshold": THRESHOLD, "window_min": WINDOW_MS // 60000,
            "records": [n for _, n in recs],
            "dialog_present": find_dialog() is not None}


# --------------------------------------------------------------------------- guard thread

class Guard:
    """Background thread: prevent before every launch, dismiss whenever a dialog shows up."""

    def __init__(self, interval=1.0):
        self.interval = interval
        self._stop = False
        self._thread = None
        self.dismissals = 0
        self.preventions = 0

    def start(self):
        import threading
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        return self

    def stop(self):
        self._stop = True
        if self._thread:
            self._thread.join(timeout=5)

    def _run(self):
        while not self._stop:
            try:
                if prevent(verbose=True):
                    self.preventions += 1
                if dismiss("guard loop"):
                    self.dismissals += 1
            except Exception as e:
                _log("loop error: %s" % str(e)[:100])
            time.sleep(self.interval)


def main():
    import argparse
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["state", "prevent", "clear", "dismiss", "watch"])
    ap.add_argument("--interval", type=float, default=1.0)
    a = ap.parse_args()

    if a.mode == "state":
        import json
        print(json.dumps(state(), indent=1, ensure_ascii=False))
    elif a.mode == "prevent":
        print("removed %d" % prevent())
        print("state now:", state()["counted"])
    elif a.mode == "clear":
        print("cleared %d" % clear_all())
    elif a.mode == "dismiss":
        print("dialog handled:", dismiss("cli"))
    elif a.mode == "watch":
        g = Guard(a.interval).start()
        print("watching; Ctrl-C to stop")
        try:
            while True:
                time.sleep(2)
                print("[guard] counted=%d dismissals=%d preventions=%d"
                      % (len(counted_records()), g.dismissals, g.preventions), flush=True)
        except KeyboardInterrupt:
            g.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
