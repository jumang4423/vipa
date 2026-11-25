"""
vipa: KYF39 端末を Hermes の口にする。

受信 → handle_message()。送信 → 箱に積む（端末が5秒ごとに取りに来る）。
UI も別サーバーも持たない。画面は APK の中。

    KYF39（アプリ）
      POST /api/voice        声（Groq で文字起こし）→ エージェント
      POST /api/in           文字 → エージェント
      GET  /api/out?since=N  5秒ごとに返信を取る
      GET  /api/health

環境変数（config.yaml の gateway.platforms.vipa.extra でも可）:
    VIPA_LISTEN    待受。既定 0.0.0.0:8099
    VIPA_SECRET    共有トークン（任意）
    GROQ_API_KEY   音声を使うなら
"""
import asyncio
import json
import logging
import os
import time

from aiohttp import web

from gateway.config import Platform, PlatformConfig
from gateway.platforms._shared import extra_or_secret
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent, MessageType

log = logging.getLogger(__name__)

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
STT_MODEL = "whisper-large-v3-turbo"
MAX_OUT = 200

CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, X-Vipa-Secret",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
}


class VipaAdapter(BasePlatformAdapter):
    def __init__(self, config: PlatformConfig):
        super().__init__(config, Platform("vipa"))
        self.secret = extra_or_secret(config.extra, "secret", "VIPA_SECRET") or ""
        self.listen = extra_or_secret(config.extra, "listen", "VIPA_LISTEN") or "0.0.0.0:8099"
        self.groq_key = extra_or_secret(config.extra, "groq_api_key", "GROQ_API_KEY") or ""
        self.chat_id = "vipa"
        self._out = []
        self._seq = 0
        self._runner = None
        self._q = asyncio.Queue()
        self._worker = None

    # ---------------------------------------------------------------- lifecycle

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        app = web.Application(client_max_size=32 * 1024 * 1024)
        app.on_response_prepare.append(self._cors)
        app.router.add_get("/api/health", self._health)
        app.router.add_get("/api/out", self._out_handler)
        app.router.add_post("/api/in", self._in)
        app.router.add_post("/api/voice", self._voice)
        app.router.add_route("OPTIONS", "/{tail:.*}", self._options)

        host, _, port = self.listen.partition(":")
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, host or "0.0.0.0", int(port or 8099))
        await site.start()
        self._worker = asyncio.ensure_future(self._drain())
        self._mark_connected()
        log.info("vipa: listening on %s", self.listen)
        return True

    async def disconnect(self) -> None:
        if self._worker is not None:
            self._worker.cancel()
            self._worker = None
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
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

    async def _out_handler(self, request):
        try:
            since = int(request.query.get("since", 0))
        except ValueError:
            since = 0
        if since > self._seq:      # 再起動で番号が戻った → 取り直させる
            since = 0
        rows = [m for m in self._out if m["seq"] > since]
        return web.json_response({"messages": rows[-40:], "last": self._seq})

    # ---------------------------------------------------------------- inbound

    async def _in(self, request):
        if not self._allowed(request):
            return web.json_response({"error": "forbidden"}, status=403)
        try:
            body = await request.json()
        except Exception:
            return web.json_response({"error": "bad json"}, status=400)
        text = (body.get("text") or "").strip()
        if not text:
            return web.json_response({"error": "empty"}, status=400)
        await self._deliver(text, body.get("user") or "vipa")
        return web.json_response({"ok": True}, status=202)

    async def _voice(self, request):
        if not self._allowed(request):
            return web.json_response({"error": "forbidden"}, status=403)
        if not self.groq_key:
            return web.json_response({"error": "GROQ_API_KEY がない"}, status=503)
        audio = await request.read()
        if len(audio) < 2000:
            return web.json_response({"error": "too small"}, status=400)
        ctype = request.headers.get("Content-Type") or "audio/mp4"
        try:
            text = await self._transcribe(audio, ctype)
        except Exception as e:
            log.warning("vipa: stt failed: %s", e)
            return web.json_response({"error": "stt: %s" % e}, status=502)
        if not text:
            return web.json_response({"error": "empty"}, status=400)
        await self._deliver(text, "vipa")
        return web.json_response({"ok": True, "text": text}, status=202)

    async def _deliver(self, text, user):
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
        self._q.put_nowait(event)

    # ---------------------------------------------------------------- outbound

    async def send(self, chat_id, content, reply_to=None, metadata=None):
        text = (content or "").strip()
        if not text:
            return SendResult(success=False)
        seq = self._push("assistant", text)
        return SendResult(success=True, message_id=str(seq))

    async def send_typing(self, chat_id):
        return None

    async def get_chat_info(self, chat_id):
        return {"name": "vipaOS", "type": "dm"}

    # ---------------------------------------------------------------- http glue

    def _allowed(self, request):
        return (not self.secret) or request.headers.get("X-Vipa-Secret") == self.secret

    async def _health(self, request):
        return web.json_response({"ok": True, "seq": self._seq,
                                  "stt": bool(self.groq_key)})

    async def _options(self, request):
        return web.Response(status=204, headers=CORS)

    @staticmethod
    async def _cors(request, response):
        for k, v in CORS.items():
            response.headers[k] = v

    # ---------------------------------------------------------------- stt

    async def _transcribe(self, audio, content_type):
        boundary = "----vipa%d" % int(time.time() * 1000)
        head = ("--%s\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n%s\r\n"
                "--%s\r\nContent-Disposition: form-data; name=\"file\"; "
                "filename=\"rec.m4a\"\r\nContent-Type: %s\r\n\r\n"
                % (boundary, STT_MODEL, boundary, content_type)).encode()
        tail = ("\r\n--%s--\r\n" % boundary).encode()
        key = self.groq_key

        def do_post():
            import ssl
            import urllib.request

            ctx = None
            try:
                import certifi
                ctx = ssl.create_default_context(cafile=certifi.where())
            except Exception:
                ctx = ssl.create_default_context()

            req = urllib.request.Request(
                GROQ_URL, data=head + audio + tail,
                headers={"Authorization": "Bearer " + key,
                         "Content-Type": "multipart/form-data; boundary=" + boundary})
            with urllib.request.urlopen(req, timeout=90, context=ctx) as r:
                return json.load(r).get("text", "").strip()

        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, do_post)


def check_requirements() -> bool:
    return True


def register(ctx):
    ctx.register_platform(
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
