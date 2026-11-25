"""
vipaOS プラグイン（Hermes の platform）。

これ1つで、端末に配る画面・受信・送信・音声入力まで面倒を見る。
別にサーバーを立てない。Hermes が頭で、このアダプタが口になる。

    KYF39（アプリ）
      ├─ GET  /             画面
      ├─ GET  /api/stream   SSE（差分・状態）
      ├─ GET  /api/messages 履歴
      ├─ POST /api/in       文字で送る
      └─ POST /api/voice    声で送る（Groq で文字起こし）
                  │
                  ▼
            handle_message → Hermes → send() → SSE で端末へ

環境変数:
    VIPA_URL     このプラグインの外から見た URL（ngrok など）。空なら localhost
    VIPA_SECRET  共有トークン（任意）
    VIPA_LISTEN  待受（既定 0.0.0.0:8099）
    GROQ_API_KEY 音声入力を使う場合のみ
"""
import asyncio
import json
import logging
import os
import time

from aiohttp import web

from gateway.config import Platform, PlatformConfig
from gateway.platforms.base import BasePlatformAdapter, SendResult
from gateway.platforms.event import MessageEvent, MessageType

log = logging.getLogger(__name__)

HERE = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(HERE, "ui", "index.html")

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
STT_MODEL = "whisper-large-v3-turbo"


class VipaAdapter(BasePlatformAdapter):
    def __init__(self, config: PlatformConfig):
        super().__init__(config, Platform("vipa"))
        self.base = (os.environ.get("VIPA_URL") or "").rstrip("/")
        self.secret = os.environ.get("VIPA_SECRET") or ""
        self.listen = os.environ.get("VIPA_LISTEN") or "0.0.0.0:8099"
        self.groq_key = os.environ.get("GROQ_API_KEY") or ""

        self._runner = None
        self._subs = set()
        self._history = []
        self._seq = 0
        self._chat = "default"

    # ------------------------------------------------------------ lifecycle

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        app = web.Application(client_max_size=32 * 1024 * 1024)
        app.router.add_get("/", self._ui)
        app.router.add_get("/api/stream", self._stream)
        app.router.add_get("/api/messages", self._messages)
        app.router.add_post("/api/in", self._in)
        app.router.add_post("/api/voice", self._voice)
        app.router.add_get("/api/health", self._health)

        host, _, port = self.listen.partition(":")
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        site = web.TCPSite(self._runner, host or "0.0.0.0", int(port or 8099))
        await site.start()
        self._mark_connected()
        log.info("vipa: listening on %s (public: %s)", self.listen, self.base or "-")
        return True

    async def disconnect(self) -> None:
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
        self._mark_disconnected()

    # ------------------------------------------------------------ http

    def _allowed(self, request):
        return (not self.secret) or request.headers.get("X-Vipa-Secret") == self.secret

    async def _health(self, request):
        return web.json_response({"ok": True, "messages": len(self._history)})

    async def _ui(self, request):
        try:
            body = open(UI, "rb").read()
        except Exception:
            return web.Response(text="ui/index.html がない", status=500)
        return web.Response(body=body, content_type="text/html", charset="utf-8")

    async def _messages(self, request):
        limit = int(request.query.get("limit", 8))
        before = int(request.query.get("before", 10 ** 9))
        rows = [m for m in self._history if m["seq"] < before]
        page = rows[-limit:]
        return web.json_response({"messages": page,
                                  "has_more": len(rows) > len(page)})

    async def _stream(self, request):
        resp = web.StreamResponse()
        resp.headers["Content-Type"] = "text/event-stream; charset=utf-8"
        resp.headers["Cache-Control"] = "no-cache"
        await resp.prepare(request)
        queue = asyncio.Queue()
        self._subs.add(queue)
        try:
            while True:
                try:
                    ev = await asyncio.wait_for(queue.get(), timeout=15)
                except asyncio.TimeoutError:
                    await resp.write(b": ping\n\n")
                    continue
                payload = "data: %s\n\n" % json.dumps(ev, ensure_ascii=False)
                await resp.write(payload.encode())
        except (asyncio.CancelledError, ConnectionResetError, RuntimeError):
            pass
        finally:
            self._subs.discard(queue)
        return resp

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
        await self._ingest(text, body.get("user") or "vipa")
        return web.json_response({"ok": True})

    async def _voice(self, request):
        if not self.app_has_stt():
            return web.json_response({"error": "GROQ_API_KEY がない"}, status=503)
        audio = await request.read()
        if len(audio) < 2000:
            return web.json_response({"error": "too small"}, status=400)
        try:
            text = await self._transcribe(audio,
                                          request.headers.get("Content-Type")
                                          or "audio/mp4")
        except Exception as e:
            return web.json_response({"error": "stt: %s" % e}, status=502)
        if not text:
            return web.json_response({"text": ""})
        await self._ingest(text, "vipa")
        return web.json_response({"text": text})

    def app_has_stt(self):
        return bool(self.groq_key)

    # ------------------------------------------------------------ core

    def _append(self, role, text):
        self._seq += 1
        m = {"seq": self._seq, "role": role, "text": text,
             "ts": time.strftime("%Y-%m-%dT%H:%M:%S%z")}
        self._history.append(m)
        if len(self._history) > 200:
            self._history = self._history[-200:]
        return m

    def _broadcast(self, ev):
        for q in list(self._subs):
            try:
                q.put_nowait(ev)
            except Exception:
                pass

    async def _ingest(self, text, user):
        """端末からの入力をエージェントに渡す。"""
        self._broadcast({"type": "user", "message": self._append("user", text)})
        self._broadcast({"type": "status", "text": "考えています"})
        source = self.build_source(
            chat_id=self._chat,
            chat_name="vipaOS",
            chat_type="dm",
            user_id=user,
            user_name="vipa",
        )
        event = MessageEvent(
            text=text,
            message_type=MessageType.TEXT,
            source=source,
            message_id=str(int(time.time() * 1000)),
        )
        await self.handle_message(event)

    # ------------------------------------------------------------ outbound

    async def send(self, chat_id, content, reply_to=None, metadata=None):
        try:
            m = self._append("assistant", content)
            self._broadcast({"type": "done", "message": m})
            return SendResult(success=True, message_id=str(m["seq"]))
        except Exception as e:
            log.warning("vipa: send failed: %s", e)
            return SendResult(success=False)

    async def send_typing(self, chat_id):
        self._broadcast({"type": "status", "text": "考えています"})
        return None

    async def get_chat_info(self, chat_id):
        return {"name": "vipaOS", "type": "dm"}

    # ------------------------------------------------------------ stt

    async def _transcribe(self, audio, content_type):
        boundary = "----vipa%d" % int(time.time() * 1000)
        head = ("--%s\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n%s\r\n"
                "--%s\r\nContent-Disposition: form-data; name=\"file\"; "
                "filename=\"rec.m4a\"\r\nContent-Type: %s\r\n\r\n"
                % (boundary, STT_MODEL, boundary, content_type)).encode()
        tail = ("\r\n--%s--\r\n" % boundary).encode()

        def do_post():
            import urllib.request
            req = urllib.request.Request(
                GROQ_URL, data=head + audio + tail,
                headers={"Authorization": "Bearer " + self.groq_key,
                         "Content-Type": "multipart/form-data; boundary=" + boundary})
            with urllib.request.urlopen(req, timeout=90) as r:
                return json.load(r).get("text", "").strip()

        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(None, do_post)


def check_requirements() -> bool:
    return True          # 外部依存なし。URLは任意


def register(ctx):
    ctx.register_platform(
        name="vipa",
        label="vipaOS",
        adapter_factory=VipaAdapter,
        check_fn=check_requirements,
        required_env=[],
        max_message_length=2000,
        platform_hint=(
            "vipaOS は小さなガラホ端末（KYF39）。画面は 480x854、タッチパネルなし、"
            "キー4つで操作する。返答は短く1行を基本にし、絵文字を使わない。"
        ),
    )
