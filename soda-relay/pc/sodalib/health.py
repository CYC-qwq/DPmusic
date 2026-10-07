"""Health check with no reliance on console encoding.

The previous verdict compared against CJK literals that had been mangled by the PowerShell console
code page, so a healthy client (7 processes, window "汽水音乐") was reported NOT HEALTHY. Here the
titles are matched by Unicode escape, and PowerShell is asked to emit UTF-8 explicitly.

Health criteria (established this round):
  * >= 5 processes
  * at least one window titled 汽水音乐
  * no window titled 异常诊断   (the sticky crash-breaker state: 2 processes, ~3.4 KB log)
"""
import subprocess

TITLE_OK = "\u6c7d\u6c34\u97f3\u4e50"      # 汽水音乐
TITLE_BAD = "\u5f02\u5e38\u8bca\u65ad"      # 异常诊断

PS = ("[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
      "Get-Process SodaMusic -ErrorAction SilentlyContinue | "
      "ForEach-Object { \"$($_.Id)|$($_.MainWindowTitle)\" }")


def rows():
    out = subprocess.run(["powershell.exe", "-NoProfile", "-Command", PS],
                         capture_output=True, encoding="utf-8", errors="replace")
    return [x.strip() for x in (out.stdout or "").splitlines() if x.strip()]


def status():
    r = rows()
    ok_title = any(TITLE_OK in x for x in r)
    bad_title = any(TITLE_BAD in x for x in r)
    healthy = len(r) >= 5 and ok_title and not bad_title
    return {"processes": len(r), "has_ok_window": ok_title, "has_diag_window": bad_title,
            "healthy": healthy, "rows": r}


if __name__ == "__main__":
    s = status()
    print("processes     : %d" % s["processes"])
    print("player window : %s" % s["has_ok_window"])
    print("diag window   : %s" % s["has_diag_window"])
    print("HEALTHY       : %s" % s["healthy"])
    for x in s["rows"]:
        print("   %s" % x)
