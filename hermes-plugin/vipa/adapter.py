"""
vipa: KYF39 端末を Hermes の口にする。

受信 → handle_message()。送信 → 箱に積む（端末が5秒ごとに取りに来る）。
UI も別サーバーも持たない。画面は APK の中。
**標準ライブラリだけで動く**（aiohttp 等は使わない）。

    KYF39（アプリ）
      POST /api/voice        声（Groq で文字起こし）→ エージェント
      POST /api/in           文字 → エージェント
      GET  /api/out?since=N  5秒ごとに返信を取る
      POST /api/reset        箱を空にする
      GET  /api/health

環境変数（config.yaml の gateway.platforms.vipa.extra でも可）:
    VIPA_LISTEN    待受。既定 0.0.0.0:8099
    GROQ_API_KEY   音声を使うなら
"""
import asyncio
import json
import logging
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from gateway.config import Platform, PlatformConfig
from gateway.platforms._shared import extra_or_secret
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent, MessageType

log = logging.getLogger(__name__)

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
STT_MODEL = "whisper-large-v3-turbo"
MAX_OUT = 200
MAX_BODY = 32 * 1024 * 1024

CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
}



def _ssl_context():
    """CA バンドルを探す。certifi が無い環境（Hermes 同梱の Python など）でも通す。"""
    import ssl
    paths = []
    try:
        import certifi
        paths.append(certifi.where())
    except Exception:
        pass
    paths += ["/etc/ssl/cert.pem",
              "/etc/ssl/certs/ca-certificates.crt",
              "/usr/local/etc/openssl/cert.pem"]
    for path in paths:
        if path and os.path.exists(path):
            try:
                return ssl.create_default_context(cafile=path)
            except Exception:
                pass
    return ssl.create_default_context()


class VipaAdapter(BasePlatformAdapter):
    def __init__(self, config: PlatformConfig):
        super().__init__(config, Platform("vipa"))
        self.listen = extra_or_secret(config.extra, "listen", "VIPA_LISTEN") or "0.0.0.0:8099"
        self.groq_key = extra_or_secret(config.extra, "groq_api_key", "GROQ_API_KEY") or ""
        self.chat_id = "vipa"

        self._out = []
        self._seq = 0
        self._lock = threading.Lock()
        self._q = None            # asyncio.Queue（loop の中で作る）
        self._loop = None
        self._worker = None
        self._server = None
        self._thread = None

    # ---------------------------------------------------------------- lifecycle

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        self._loop = asyncio.get_running_loop()
        if self._q is None:
            self._q = asyncio.Queue()

        host, _, port = self.listen.partition(":")
        try:
            self._server = ThreadingHTTPServer(
                (host or "0.0.0.0", int(port or 8099)), self._make_handler())
        except OSError as e:
            log.error("vipa: cannot listen on %s (%s)", self.listen, e)
            return False
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever,
                                        name="vipa-http", daemon=True)
        self._thread.start()
        self._worker = asyncio.ensure_future(self._drain())
        self._mark_connected()
        log.info("vipa: listening on %s", self.listen)
        return True

    async def disconnect(self) -> None:
        if self._worker is not None:
            self._worker.cancel()
            self._worker = None
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
        self._mark_disconnected()

    async def _drain(self):
        """エージェントの1ターンは長い。受信は即返し、ここで順番に走らせる。"""
        while True:
            event = await self._q.get()
            try:
                await self.handle_message(event)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("vipa: handle_message failed: %s", e)

    # ---------------------------------------------------------------- outbox

    def _push(self, role, text):
        with self._lock:
            self._seq += 1
            self._out.append({
                "seq": self._seq,
                "role": role,
                "text": text,
                "ts": int(time.time() * 1000),
            })
            if len(self._out) > MAX_OUT:
                del self._out[:len(self._out) - MAX_OUT]
            return self._seq

    def _since(self, since):
        with self._lock:
            if since > self._seq:          # 再起動で番号が戻った → 取り直させる
                since = 0
            rows = [m for m in self._out if m["seq"] > since]
            return {"messages": rows[-40:], "last": self._seq}

    # ---------------------------------------------------------------- inbound

    def _deliver(self, text, user="vipa"):
        """端末の入力を積む。受信の応答は待たせない。"""
        self._push("user", text)
        source = self.build_source(
            chat_id=self.chat_id,
            chat_name="vipaOS",
            chat_type="dm",
            user_id=user,
            user_name=user,
        )
        event = MessageEvent(
            text=text,
            message_type=MessageType.TEXT,
            source=source,
            message_id=str(int(time.time() * 1000)),
        )
        self._loop.call_soon_threadsafe(self._q.put_nowait, event)

    # ---------------------------------------------------------------- outbound

    async def send(self, chat_id, content, reply_to=None, metadata=None):
        text = (content or "").strip()
        if not text:
            return SendResult(success=False)
        return SendResult(success=True, message_id=str(self._push("assistant", text)))

    async def send_typing(self, chat_id):
        return None

    async def get_chat_info(self, chat_id):
        return {"name": "vipaOS", "type": "dm"}

    # ---------------------------------------------------------------- stt

    async def _transcribe(self, audio, content_type):
        return await asyncio.get_running_loop().run_in_executor(
            None, self._transcribe_sync, audio, content_type)

    def _transcribe_sync(self, audio, content_type):
        import ssl
        import urllib.request

        boundary = "----vipa%d" % int(time.time() * 1000)
        head = ("--%s\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n%s\r\n"
                "--%s\r\nContent-Disposition: form-data; name=\"file\"; "
                "filename=\"rec.m4a\"\r\nContent-Type: %s\r\n\r\n"
                % (boundary, STT_MODEL, boundary, content_type)).encode()
        tail = ("\r\n--%s--\r\n" % boundary).encode()
        ctx = _ssl_context()

        req = urllib.request.Request(
            GROQ_URL, data=head + audio + tail,
            headers={"Authorization": "Bearer " + self.groq_key,
                     "User-Agent": "vipa/1.0",   # これが無いと Cloudflare が 403 で弾く
                     "Content-Type": "multipart/form-data; boundary=" + boundary})
        with urllib.request.urlopen(req, timeout=90, context=ctx) as r:
            return json.load(r).get("text", "").strip()

    # ---------------------------------------------------------------- http

    def _make_handler(self):
        adapter = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                return

            # -- helpers
            def _send(self, code, obj):
                body = json.dumps(obj, ensure_ascii=False).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                for k, v in CORS.items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(body)

            def _body(self):
                try:
                    n = int(self.headers.get("Content-Length") or 0)
                except ValueError:
                    return b""
                if n <= 0 or n > MAX_BODY:
                    return b""
                return self.rfile.read(n)

            # -- routes
            def do_OPTIONS(self):
                self._send(204, {})

            def do_GET(self):
                path, _, query = self.path.partition("?")
                if path == "/api/health":
                    self._send(200, {"ok": True, "seq": adapter._seq,
                                     "stt": bool(adapter.groq_key)})
                elif path == "/api/out":
                    since = 0
                    for kv in query.split("&"):
                        k, _, v = kv.partition("=")
                        if k == "since":
                            try:
                                since = int(v)
                            except ValueError:
                                since = 0
                    self._send(200, adapter._since(since))
                else:
                    self._send(404, {"error": "not found"})

            def do_POST(self):
                path, _, _ = self.path.partition("?")

                if path == "/api/in":
                    raw = self._body()
                    try:
                        text = (json.loads(raw.decode() or "{}").get("text") or "").strip()
                    except Exception:
                        self._send(400, {"error": "bad json"})
                        return
                    if not text:
                        self._send(400, {"error": "empty"})
                        return
                    adapter._deliver(text)
                    self._send(202, {"ok": True})
                    return

                if path == "/api/reset":
                    with adapter._lock:
                        del adapter._out[:]
                        adapter._seq = 0
                    self._send(200, {"ok": True})
                    return

                if path == "/api/voice":
                    if not adapter.groq_key:
                        self._send(503, {"error": "GROQ_API_KEY がない"})
                        return
                    audio = self._body()
                    if len(audio) < 2000:
                        self._send(400, {"error": "too small"})
                        return
                    ctype = self.headers.get("Content-Type") or "audio/mp4"
                    try:
                        text = adapter._transcribe_sync(audio, ctype)
                    except Exception as e:
                        log.warning("vipa: stt failed: %s", e)
                        self._send(502, {"error": "stt: %s" % e})
                        return
                    if not text:
                        self._send(400, {"error": "empty"})
                        return
                    adapter._deliver(text)
                    self._send(202, {"ok": True, "text": text})
                    return

                self._send(404, {"error": "not found"})

        return Handler


def check_requirements() -> bool:
    return True


def register(ctx):
    kwargs = dict(
        name="vipa",
        label="vipaOS",
        adapter_factory=VipaAdapter,
        check_fn=check_requirements,
        required_env=[],
        max_message_length=2000,
        platform_hint=(
            "vipaOS は小さなガラホ端末（KYF39、480x854、タッチパネルなし、キー4つ）。"
            "返答は短く1行を基本にし、絵文字を使わない。話しかけられたときだけ答える。"
        ),
    )
    # 端末は家の中の1台。承認（ペアリング）を挟まない。
    kwargs["allowed_users_env"] = "VIPA_ALLOWED_USERS"
    kwargs["allow_all_env"] = "VIPA_ALLOW_ALL_USERS"
    try:
        ctx.register_platform(**kwargs)
    except TypeError:
        for key in ("allowed_users_env", "allow_all_env"):
            kwargs.pop(key, None)
        ctx.register_platform(**kwargs)
