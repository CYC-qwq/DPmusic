"""Zero-dependency relay: stdlib only, no pip install needed.

Why: the box has 473 MB RAM, 1.5 GB free disk and no pip, and `apt-get install python3-pip` plus a
websockets wheel is both slow and unnecessary. The WebSocket protocol the daemon needs is small, and
implementing just that subset removes every external dependency:

  * RFC 6455 handshake (Sec-WebSocket-Key -> Accept via SHA1+base64)
  * text frames with masking (client->server frames are always masked)
  * ping/pong and close
  * fragmentation and large payloads are handled, though neither is expected here

The device-facing side stays plain HTTP. Audio is not relayed through the daemon: the relay already
holds the CDN url, so `/v1/audio` fetches it directly and forwards the same Range, preserving
seeking on the device.

Endpoints:
  GET /agent            WebSocket upgrade for the home daemon
  GET /v1/health        liveness + agent connection state
  GET /v1/play          ?track_id=&quality=   -> json with the CDN url
  GET /v1/audio         ?track_id=&quality=   -> audio bytes, Range forwarded
"""
import base64
import hashlib
import hmac
import json
import os
import socket
import socketserver
import struct
import sys
import threading
import time
import urllib.parse
import urllib.request

VER = 1
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
ALLOWED_QUALITIES = ("lossless", "hi_res", "spatial", "highest", "higher", "medium")
# `lossless` is offered because it was proven obtainable, not assumed: the client's own ladder
# carries a lossless entry (MP4-wrapped FLAC, frma='fLaC', 44100 Hz/16-bit, ~1 Mbps, ~28 MB per
# track), its key decrypts it to an ffmpeg-clean decode (rc=0, zero errors), and the ENCRYPTED stream
# still exposes dfLa STREAMINFO so duration verification keeps working unchanged.
# It stays opt-in rather than default because of the size.
UA = "LunaPC/3.8.0(467160162)"
MAX_AUDIO_BUFFER = 16 * 1024 * 1024   # a whole track fits; anything larger streams after the head
# The CDN host resolves to many edge IPs and some edges hang. Measured: one attempt sat for 60 s and
# then raised RemoteDisconnected, while the next attempt succeeded in under 1 s, and a separate
# sample took 47 s. So fail fast and retry rather than wait: 6 attempts of 6 s stay inside a 40 s
# budget that a player can tolerate.
CDN_ATTEMPT_TIMEOUT_S = 6
CDN_ATTEMPTS = 6
CDN_TOTAL_BUDGET_S = 40
REASONS = {200: "OK", 206: "Partial Content", 400: "Bad Request", 401: "Unauthorized",
           404: "Not Found", 500: "Internal Server Error", 502: "Bad Gateway",
           503: "Service Unavailable"}


def log(*a):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), " ".join(str(x) for x in a)), flush=True)


def sign(secret, payload):
    return hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()


# --------------------------------------------------------------------------- WebSocket framing

def ws_accept(key):
    return base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()


def ws_send_text(sock, text):
    ws_send_frame(sock, 0x1, text.encode())


def ws_send_frame(sock, opcode, payload, mask=False):
    b1 = 0x80 | opcode
    n = len(payload)
    if n < 126:
        header = struct.pack("!BB", b1, (0x80 if mask else 0) | n)
    elif n < 65536:
        header = struct.pack("!BBH", b1, (0x80 if mask else 0) | 126, n)
    else:
        header = struct.pack("!BBQ", b1, (0x80 if mask else 0) | 127, n)
    if mask:
        key = os.urandom(4)
        payload = bytes(c ^ key[i % 4] for i, c in enumerate(payload))
        header += key
    sock.sendall(header + payload)


def ws_recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return buf


def ws_recv_frame(sock, lock=None):
    """Return (opcode, payload). Raises ConnectionError when the peer closes.

    Iterative, not recursive: control frames (ping/pong) arrive interleaved with data, and a
    recursive version grows the stack on every one. The daemon pings every 20 s, so an
    unacknowledged ping is what makes it drop the connection.

    `lock` guards the pong write. Sends happen from two threads -- the HTTP worker answering a
    device, and this reader acknowledging a ping -- and interleaving two frames on one socket
    produces a stream the peer cannot parse (observed as the daemon resetting the connection
    exactly 20 s after each request).
    """
    while True:
        h = ws_recv_exact(sock, 2)
        b1, b2 = h[0], h[1]
        opcode = b1 & 0x0F
        masked = b2 & 0x80
        n = b2 & 0x7F
        if n == 126:
            n = struct.unpack("!H", ws_recv_exact(sock, 2))[0]
        elif n == 127:
            n = struct.unpack("!Q", ws_recv_exact(sock, 8))[0]
        key = ws_recv_exact(sock, 4) if masked else b""
        payload = ws_recv_exact(sock, n) if n else b""
        if masked:
            payload = bytes(c ^ key[i % 4] for i, c in enumerate(payload))
        if opcode == 0x9:                      # ping -> pong, then keep reading
            try:
                if lock is not None:
                    with lock:
                        ws_send_frame(sock, 0xA, payload)
                else:
                    ws_send_frame(sock, 0xA, payload)
            except Exception:
                raise ConnectionError("pong failed")
            continue
        if opcode == 0xA:                      # pong: not a data frame
            continue
        return opcode, payload


# --------------------------------------------------------------------------------- the hub

class Hub:
    def __init__(self, secret):
        self.secret = secret
        self.ws = None
        self.lock = threading.Lock()          # guards every frame write, incl. ping pongs
        self.waiters = {}
        self.seq = 0
        self.connected_at = None
        self.last_error = None
        self.cond = threading.Condition()

    def send_text(self, text):
        """Serialise all frame writes on the agent socket.

        Two threads write here: the HTTP worker sending a request, and the reader replying to a
        ping. Without this lock the frames interleave and the peer sees an unparseable stream,
        which is exactly what happened -- the daemon reset the connection 20 s (its ping interval)
        after each successful request.
        """
        with self.lock:
            sock = self.ws
            if sock is None:
                raise RuntimeError("agent not connected")
            ws_send_text(sock, text)

    def next_id(self):
        with self.lock:
            self.seq += 1
            return "req-%d-%d" % (int(time.time()), self.seq)

    def call(self, kind, payload, timeout=170):
        """Send a request to the daemon and block until its reply arrives."""
        if self.ws is None:
            raise RuntimeError("agent not connected")
        rid = self.next_id()
        box = {"msg": None}
        with self.cond:
            self.waiters[rid] = box
        ts = int(time.time())
        msg = {"type": kind, "id": rid, "ts": ts,
               "sig": sign(self.secret, "%s.%s" % (kind, ts))}
        msg.update(payload)
        try:
            self.send_text(json.dumps(msg, ensure_ascii=False))
        except Exception as e:
            self.waiters.pop(rid, None)
            raise RuntimeError("send failed: %s" % str(e)[:80])
        deadline = time.time() + timeout
        with self.cond:
            while box["msg"] is None:
                remain = deadline - time.time()
                if remain <= 0:
                    self.waiters.pop(rid, None)
                    raise TimeoutError("daemon did not reply within %ds" % timeout)
                self.cond.wait(remain)
            self.waiters.pop(rid, None)
        return box["msg"]

    def resolve(self, msg):
        rid = msg.get("id")
        with self.cond:
            box = self.waiters.get(rid)
            if box is not None:
                box["msg"] = msg
                self.cond.notify_all()


# ------------------------------------------------------------------------- HTTP for devices

class DeviceHandler(socketserver.StreamRequestHandler):
    hub = None
    device_secret = ""

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        """Write status line, headers and body in ONE write.

        `StreamRequestHandler.wbufsize` is 0, so every write goes straight to the socket. Writing
        the response in pieces means a failure after the first piece leaves the peer with a bare
        status line and no headers -- which surfaces as a 502 with an EMPTY body, exactly the
        intermittent symptom observed on /v1/audio. One write cannot be split.

        The except clause LOGS rather than passing silently: a swallowed write error is
        indistinguishable from a request that was never handled, and that silence is what made the
        intermittent failure hard to attribute.
        """
        try:
            head = ["HTTP/1.1 %d %s" % (code, REASONS.get(code, "Error")),
                    "Content-Type: %s" % ctype,
                    "Content-Length: %d" % len(body)]
            for k, v in (extra or {}).items():
                head.append("%s: %s" % (k, v))
            head.append("Connection: close")
            rid = getattr(self, "_rid", "-")
            self.wfile.write(("\r\n".join(head) + "\r\n\r\n").encode() + body)
            log("resp %s -> %d (%d bytes)" % (rid, code, len(body)))
        except Exception as e:
            log("resp %s -> WRITE FAILED for %d: %s: %s"
                % (getattr(self, "_rid", "-"), code, type(e).__name__, str(e)[:110]))

    def _json(self, code, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode())

    def _auth(self, path, headers):
        ts = headers.get("x-ts", "")
        sig = headers.get("x-sig", "")
        try:
            tsi = int(ts)
        except (TypeError, ValueError):
            return False, "missing or invalid X-Ts"
        if abs(time.time() - tsi) > 300:
            return False, "timestamp outside the 300 s window"
        if not hmac.compare_digest(sign(self.device_secret, "device.%d.%s" % (tsi, path)),
                                   sig or ""):
            return False, "signature mismatch"
        return True, ""

    def handle(self):
        try:
            line = self.rfile.readline(65536).decode("latin-1").strip()
            if not line:
                return
            parts = line.split()
            if len(parts) < 2:
                return
            method, target = parts[0], parts[1]

            # socketserver's StreamRequestHandler does NOT provide `self.headers` (that is
            # http.server's BaseHTTPRequestHandler). Parse them here; without this every device
            # request died with "'DeviceHandler' object has no attribute 'headers'" before any
            # response was written, which surfaced as a bare connection close.
            headers = {}
            while True:
                ln = self.rfile.readline(65536).decode("latin-1").strip()
                if not ln:
                    break
                if ":" in ln:
                    k, v = ln.split(":", 1)
                    headers[k.strip().lower()] = v.strip()

            u = urllib.parse.urlparse(target)
            path, q = u.path, urllib.parse.parse_qs(u.query)
            # a short id so a request's "begin" and "resp" lines can be matched even when several
            # overlap; without it an unlogged request is invisible
            self._rid = "%04x" % (int(time.time() * 1000) & 0xFFFF)
            log("req %s %s %s" % (self._rid, method, path))

            if path == "/v1/health":
                return self._json(200, {"ok": True,
                                        "agent_connected": self.hub.ws is not None,
                                        "since": self.hub.connected_at,
                                        "waiters": len(self.hub.waiters),
                                        "last_error": self.hub.last_error})
            ok, why = self._auth(path, headers)
            if not ok:
                return self._json(401, {"ok": False, "code": "unauthorized", "message": why})
            if path == "/v1/play":
                return self._play(q, headers)
            if path == "/v1/audio":
                return self._audio(q, headers)
            return self._json(404, {"ok": False, "code": "not_found"})
        except Exception as e:
            log("device handler error: %s" % str(e)[:120])

    def _resolve(self, q):
        track_id = (q.get("track_id") or [""])[0]
        quality = (q.get("quality") or ["hi_res"])[0]
        if not track_id.isdigit():
            return None, {"ok": False, "code": "bad_request",
                          "message": "track_id must be digits"}
        if quality not in ALLOWED_QUALITIES:
            return None, {"ok": False, "code": "bad_request",
                          "message": "quality must be one of %s"
                                     % "/".join(ALLOWED_QUALITIES)}
        try:
            res = self.hub.call("play", {"track_id": track_id, "quality": quality})
        except Exception as e:
            self.hub.last_error = str(e)[:120]
            return None, {"ok": False, "code": "agent_unavailable", "message": str(e)[:120]}
        return res, None

    def _play(self, q, headers):
        res, err = self._resolve(q)
        if err:
            return self._json(503 if err["code"] == "agent_unavailable" else 400, err)
        body = {"ok": bool(res.get("ok")), "url": res.get("url"), "quality": res.get("quality"),
                "codec": res.get("codec"), "duration_s": res.get("duration_s"),
                "catalogue_s": res.get("catalogue_s"),
                "is_full_length": res.get("is_full_length"),
                # CENC key material, forwarded verbatim from the daemon. The device decrypts
                # locally, so no audio has to traverse this box. `play_auth` is the name the
                # device protocol expects; the client stores it as `encrypt_info.spade_a`.
                # It belongs strictly to THIS url -- every tier has its own key, and a mismatched
                # one decrypts to noise.
                "play_auth": res.get("play_auth", ""),
                "encrypted": res.get("encrypted", False),
                "encryption": res.get("encryption"),
                "membership": res.get("membership"), "cache": res.get("cache"),
                "took_ms": res.get("took_ms"), "expires_hint_s": res.get("expires_hint_s"),
                "code": res.get("code"), "message": res.get("message")}
        return self._json(200 if body["ok"] else 502, body)

    def _audio(self, q, req_headers):
        res, err = self._resolve(q)
        if err:
            log("audio: resolve error %s" % err)
            return self._json(503 if err["code"] == "agent_unavailable" else 400, err)
        if not res.get("ok"):
            # Log the daemon's own error verbatim: the device only sees the code, and a bare 502
            # with no explanation is what made the first public run hard to diagnose.
            log("audio: daemon refused %s" % json.dumps(
                {k: res.get(k) for k in ("ok", "code", "message", "id", "took_ms")}))
            return self._json(502, {"ok": False, "code": res.get("code"),
                                    "message": res.get("message")})

        # The Range must come from the INBOUND request and be applied to the OUTBOUND one. Keep them
        # in separate variables: an earlier version rebound `headers` to the outbound dict first, so
        # the inbound Range was already discarded when it was looked up, and every `/v1/audio` call
        # returned the whole file instead of the requested slice.
        rng = req_headers.get("range") or (q.get("range") or [None])[0]
        out_headers = {"User-Agent": UA}
        if rng:
            out_headers["Range"] = rng

        # Read the payload BEFORE writing the status line. The CDN is occasionally slow (measured
        # 8-9 s for a 1 KB range, once over 60 s), and a client cannot be handed a 206 and then be
        # left with nothing -- that is what produced the intermittent "502 with an empty body".
        # A player needs seeking, so the byte count here is the requested slice, not the whole file.
        #
        # Short per-attempt timeout + more attempts, and a total budget. The CDN host resolves to
        # many edge IPs and a bad edge simply hangs: measured, one attempt sat for 60 s and then
        # raised RemoteDisconnected, while the very next attempt succeeded in under 1 s. Waiting
        # longer is the wrong response -- giving up early and taking a different edge is right, and
        # it keeps the whole call inside a client-friendly budget.
        cdn_err = None
        deadline = time.time() + CDN_TOTAL_BUDGET_S
        upstream = None
        for attempt in range(1, CDN_ATTEMPTS + 1):
            left = deadline - time.time()
            if left <= 1:
                break
            try:
                upstream = urllib.request.urlopen(
                    urllib.request.Request(res["url"], headers=out_headers),
                    timeout=min(CDN_ATTEMPT_TIMEOUT_S, left))
                body = upstream.read(MAX_AUDIO_BUFFER + 1)
                over = len(body) > MAX_AUDIO_BUFFER
                status = upstream.status
                hdrs = dict(upstream.headers)
                cdn_err = None
                break
            except Exception as e:
                cdn_err = "%s: %s" % (type(e).__name__, str(e)[:110])
                log("audio: cdn attempt %d/%d failed after %.1fs: %s"
                    % (attempt, CDN_ATTEMPTS, min(CDN_ATTEMPT_TIMEOUT_S, left), cdn_err))
                upstream = None
                time.sleep(0.4)
        if cdn_err is not None or upstream is None:
            log("audio: cdn gave up after %d attempts (%s)" % (CDN_ATTEMPTS, cdn_err))
            return self._json(502, {"ok": False, "code": "cdn_error", "message": cdn_err})

        log("audio: upstream status=%s bytes=%d in %.1fs (attempt %d) range=%s"
            % (status, len(body), CDN_TOTAL_BUDGET_S - (deadline - time.time()), attempt,
               hdrs.get("Content-Range") or hdrs.get("Content-Length")))
        extra = {"Content-Type": hdrs.get("Content-Type") or "audio/mp4",
                 "Accept-Ranges": "bytes"}
        for k in ("Content-Range", "Content-Length"):
            if hdrs.get(k) and not over:
                extra[k] = hdrs[k]
        # Requirement A also applies here: the fallback serves the SAME encrypted bytes, so the
        # caller needs the matching key. It goes in headers because the body is audio.
        extra["X-Encrypted"] = "1" if res.get("encrypted") else "0"
        if res.get("encryption"):
            extra["X-Encryption"] = str(res["encryption"])
        if res.get("play_auth"):
            extra["X-Play-Auth"] = str(res["play_auth"])
        # status line + headers + first chunk in a single write
        self._send(status, body[:MAX_AUDIO_BUFFER] if over else body, extra=extra)
        if over:                                  # stream the remainder (no Range was requested)
            try:
                while True:
                    chunk = upstream.read(262144)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
            except Exception as e:
                log("audio: stream cut short: %s" % str(e)[:80])
        if upstream is not None:
            upstream.close()


# --------------------------------------------------------------------- WebSocket for the agent

class AgentHandler(socketserver.BaseRequestHandler):
    hub = None
    secret = ""

    def handle(self):
        sock = self.request
        try:
            data = b""
            while b"\r\n\r\n" not in data:
                chunk = sock.recv(4096)
                if not chunk:
                    return
                data += chunk
                if len(data) > 65536:
                    return
            head = data.decode("latin-1")
            lines = head.split("\r\n")
            request_line = lines[0]
            path = request_line.split()[1] if len(request_line.split()) > 1 else ""
            if not path.startswith("/agent"):
                sock.sendall(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
                return
            headers = {}
            for ln in lines[1:]:
                if ":" in ln:
                    k, v = ln.split(":", 1)
                    headers[k.strip().lower()] = v.strip()
            key = headers.get("sec-websocket-key")
            if not key:
                sock.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
                return

            sock.sendall(("HTTP/1.1 101 Switching Protocols\r\n"
                          "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                          "Sec-WebSocket-Accept: %s\r\n\r\n" % ws_accept(key)).encode())

            op, payload = ws_recv_frame(sock)
            if op != 0x1:
                return
            hello = json.loads(payload.decode("utf-8", "replace"))
            if hello.get("type") != "hello":
                log("agent did not open with hello")
                return
            ts = hello.get("ts")
            if not (abs(time.time() - int(ts or 0)) <= 300
                    and hmac.compare_digest(sign(self.secret, "hello.%s" % ts),
                                            str(hello.get("sig", "")))):
                log("agent hello signature rejected")
                return
            log("agent hello ok: node=%s caps=%s" % (hello.get("node"), hello.get("capabilities")))

            with self.hub.lock:
                self.hub.ws = sock
            self.hub.connected_at = time.time()
            try:
                while True:
                    op, payload = ws_recv_frame(sock, self.hub.lock)
                    if op == 0x8:
                        break
                    if op != 0x1:
                        continue
                    try:
                        m = json.loads(payload.decode("utf-8", "replace"))
                    except Exception:
                        continue
                    kind = m.get("type")
                    if kind == "result" or (kind is None and m.get("id")):
                        self.hub.resolve(m)
                    elif kind in ("pong", "heartbeat"):
                        pass
                    else:
                        log("agent sent unhandled: %s" % json.dumps(m)[:140])
            finally:
                self.hub.ws = None
                log("agent disconnected")
        except Exception as e:
            log("agent handler error: %s" % str(e)[:120])
        finally:
            try:
                sock.close()
            except Exception:
                pass


class ThreadedTCP(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


def main():
    secret = os.environ.get("SODA_SHARED_SECRET", "")
    device_secret = os.environ.get("DEVICE_SECRET", "")
    if not secret or not device_secret:
        log("set SODA_SHARED_SECRET and DEVICE_SECRET first")
        return 2
    http_port = int(os.environ.get("HTTP_PORT", "8080"))
    agent_port = int(os.environ.get("AGENT_PORT", "8765"))
    bind = os.environ.get("BIND", "0.0.0.0")

    hub = Hub(secret)
    DeviceHandler.hub = hub
    DeviceHandler.device_secret = device_secret
    AgentHandler.hub = hub
    AgentHandler.secret = secret

    a = ThreadedTCP((bind, agent_port), AgentHandler)
    threading.Thread(target=a.serve_forever, daemon=True).start()
    log("agent websocket listening on %s:%d/agent" % (bind, agent_port))

    d = ThreadedTCP((bind, http_port), DeviceHandler)
    log("device API on http://%s:%d/v1/{play,audio,health}" % (bind, http_port))
    try:
        d.serve_forever()
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())
