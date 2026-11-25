"""
vipa: the mouth of a KYF39 flip phone.

Inbound  -> handle_message(). Outbound -> an outbox the phone polls every 5s.
No UI, no extra server, no third-party dependencies: stdlib only.

    KYF39 (app)
      POST /api/voice        audio -> Groq transcription -> agent
      POST /api/in           {"text": "..."} -> agent
      GET  /api/out?since=N  new messages
      GET  /api/schedule     the day, from schedule.json
      GET  /api/day          the body's record for one day
      POST /api/day          append weight|meal|mood|sleep|steps|health
      GET  /api/days?n=30    weights over time, for the graph
      POST /api/push         {"text": "..."} straight to the phone, no agent
      POST /api/reset        empty the outbox
      GET  /api/health

Settings (env, or gateway.platforms.vipa.extra in config.yaml):
    VIPA_LISTEN     bind address. default 0.0.0.0:8099
    GROQ_API_KEY    required: voice is the phone's only input
"""
import asyncio
import json
import logging
import os
import re
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
# No truncation by default. Short answers are the agent's job (platform_hint says so);
# the transport must never cut a sentence. Set VIPA_MAX_CHARS to a number to re-enable.
MAX_CHARS = int(os.environ.get("VIPA_MAX_CHARS") or 0)



def condense(text, limit=None):
    """One short line, but never a sentence cut in half.

    Take whole sentences while they fit; if none fits, cut at a clause boundary. Only
    when even that fails does it hard-cut, and then it marks the cut.
    """
    import re
    limit = MAX_CHARS if limit is None else limit
    t = (text or "").strip()
    if not limit or len(t) <= limit:
        return re.sub(r"\n{3,}", "\n\n", t)
    t = re.sub(r"```.*?```", " ", t, flags=re.S)
    t = re.sub(r"(?m)^\s*(?:[-*•]|\d+[.)])\s+", "", t)
    t = re.sub(r"[*_`#>|]+", "", t)
    t = re.sub(r"\s+", " ", t).strip()
    if len(t) <= limit:
        return t

    # whole sentences first
    acc = ""
    for part in re.split(r"(?<=[。．.!?！?])", t):
        if not part:
            continue
        if len((acc + part).strip()) <= limit:
            acc += part
        else:
            break
    if acc.strip():
        return acc.strip()

    # no sentence fits: cut at a clause boundary, not mid-word
    for ch in ("、,;:）)", " "):
        cut = t.rfind(ch, 0, limit)
        if cut >= limit // 3:
            return t[:cut].rstrip(" 、,;:（(") + "\u2026"
    return t[:limit - 1].rstrip() + "\u2026"


CORS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
}


KINDS = ("weight", "meal", "mood", "sleep", "steps", "health")


def _day_path(day=None):
    import datetime
    day = day or datetime.date.today().isoformat()
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day or ""):
        day = datetime.date.today().isoformat()
    return os.path.join(DAYS, day + ".json"), day


def read_day(day=None):
    path, day = _day_path(day)
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except Exception:
        data = {}
    entries = data.get("entries") or []
    entries.sort(key=lambda e: e.get("ts") or "")
    return {"date": day, "entries": entries}


def log_entry(kind, text="", kcal=None, kg=None, ts=None):
    """Append one record for today. Shared by the HTTP endpoint and the vipa_log tool."""
    import datetime
    if kind not in KINDS:
        raise ValueError("kind must be one of " + ", ".join(KINDS))
    now = datetime.datetime.now()
    ts = ts or now.strftime("%H:%M")
    if not re.fullmatch(r"\d{1,2}:\d{2}", str(ts)):
        ts = now.strftime("%H:%M")
    hh, mm = str(ts).split(":")
    entry = {"ts": "%02d:%s" % (int(hh), mm), "kind": kind}
    if text:
        entry["text"] = str(text)
    if kcal not in (None, ""):
        try:
            entry["kcal"] = int(round(float(kcal)))
        except Exception:
            pass
    if kg not in (None, ""):
        try:
            entry["kg"] = round(float(kg), 1)
        except Exception:
            pass
    os.makedirs(DAYS, exist_ok=True)
    path, day = _day_path()
    data = read_day(day)
    data["entries"].append(entry)
    data["entries"].sort(key=lambda e: e.get("ts") or "")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    return entry


def weights(n=30):
    out = []
    try:
        names = sorted(os.listdir(DAYS))[-int(n):]
    except Exception:
        return out
    for name in names:
        if not name.endswith(".json"):
            continue
        day = read_day(name[:-5])
        kgs = [e["kg"] for e in day["entries"] if e.get("kind") == "weight" and e.get("kg")]
        if kgs:
            out.append({"date": day["date"], "kg": kgs[-1]})
    return out


def _tool_log(args, **_):
    """vipa_log: record what the body reports, in one call."""
    kind = str(args.get("kind") or "").strip().lower()
    try:
        entry = log_entry(kind, str(args.get("text") or "").strip(),
                          args.get("kcal"), args.get("kg"), args.get("ts"))
    except Exception as e:
        return "Error: %s" % e
    bits = [entry["ts"], entry["kind"]]
    if entry.get("kcal"):
        bits.append("%d kcal" % entry["kcal"])
    if entry.get("kg"):
        bits.append("%s kg" % entry["kg"])
    return "logged " + " ".join(bits) + (" — " + entry["text"] if entry.get("text") else "")


def _tool_day(args, **_):
    """vipa_day: read what the body recorded, today or any day."""
    import datetime
    day = str(args.get("date") or "").strip() or None
    data = read_day(day)
    entries = data["entries"]
    if not entries:
        return "%s: nothing recorded." % data["date"]
    kcal = sum(e.get("kcal", 0) for e in entries)
    kgs = [e["kg"] for e in entries if e.get("kind") == "weight" and e.get("kg")]
    lines = ["%s — %d entries, %d kcal%s" % (
        data["date"], len(entries), kcal,
        (", weight %s kg" % kgs[-1]) if kgs else "")]
    for e in entries:
        bits = [e.get("ts", ""), e.get("kind", "")]
        if e.get("kcal"):
            bits.append("%d kcal" % e["kcal"])
        if e.get("kg"):
            bits.append("%s kg" % e["kg"])
        line = " ".join(bits)
        if e.get("text"):
            line += ": " + e["text"]
        lines.append("- " + line)
    return "\n".join(lines)


class VipaAdapter(BasePlatformAdapter):
    def __init__(self, config: PlatformConfig):
        super().__init__(config, Platform("vipa"))
        self.listen = extra_or_secret(config.extra, "listen", "VIPA_LISTEN") or "0.0.0.0:8099"
        self.groq_key = extra_or_secret(config.extra, "groq_api_key", "GROQ_API_KEY") or ""
        # A session keeps the system prompt it was born with, so the rules only reach the
        # agent when a new session starts. Deriving the chat id from the rules themselves
        # means: edit philosophy.md or criteria.md, restart, and the next turn runs in a
        # fresh session that has read them. VIPA_CHAT still overrides, if you want to pin it.
        self.chat_id = (extra_or_secret(config.extra, "chat", "VIPA_CHAT")
                        or "vipa-" + _rules_id())
        self.inject_rules = (extra_or_secret(config.extra, "inject_rules", "VIPA_INJECT_RULES") or "0") in ("1", "true", "yes")

        self._out = []
        self._seq = 0
        self._lock = threading.Lock()
        self._q = None            # asyncio.Queue, created inside the loop
        self._loop = None
        self._worker = None
        self._reset_task = None
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
        if (extra_or_secret(getattr(self.config, "extra", {}) or {}, "daily_reset", "VIPA_DAILY_RESET") or "1") not in ("0", "false", "no"):
            self._reset_task = asyncio.ensure_future(self._daily_reset())
        self._mark_connected()
        log.info("vipa: listening on %s", self.listen)
        return True

    async def disconnect(self) -> None:
        if self._reset_task is not None:
            self._reset_task.cancel()
            self._reset_task = None
        if self._worker is not None:
            self._worker.cancel()
            self._worker = None
        self._reset_task = None
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
        self._mark_disconnected()

    def _rotate_session(self, clear=False):
        """Start a fresh session: the prompt is rebuilt from the current rules.

        Nothing is pushed to the phone. With clear=False the outbox is left alone as well,
        so a midnight rotation is invisible to someone asleep.
        """
        base = self.chat_id.split("-")[0] or "vipa"
        self.chat_id = "%s-%d" % (base, int(time.time()))
        if clear:
            self._reset()
        log.info("vipa: new session chat_id=%s (cleared=%s)", self.chat_id, clear)

    async def _daily_reset(self):
        """Rotate the session once a day, so a long chat never becomes the phone's problem."""
        import datetime
        hour = int(extra_or_secret(getattr(self.config, "extra", {}) or {}, "reset_hour", "VIPA_RESET_HOUR") or 0)
        while True:
            now = datetime.datetime.now()
            nxt = now.replace(hour=hour, minute=0, second=0, microsecond=0)
            if nxt <= now:
                nxt += datetime.timedelta(days=1)
            delay = max(5.0, (nxt - now).total_seconds())
            # test hook: VIPA_RESET_SECS=5 rotates every 5s instead of at the clock time
            override = extra_or_secret(getattr(self.config, "extra", {}) or {}, "reset_secs", "VIPA_RESET_SECS")
            if override:
                delay = max(3.0, float(override))
            if not override:
                log.info("vipa: session reset scheduled for %s", nxt.strftime("%Y-%m-%d %H:%M"))
            await asyncio.sleep(delay)
            self._rotate_session()

    async def _drain(self):
        """An agent turn takes minutes. Acknowledge fast, run turns in order here."""
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
            if since > self._seq:          # the gateway restarted; resync the phone
                since = 0
            rows = [m for m in self._out if m["seq"] > since]
            return {"messages": rows[-40:], "last": self._seq}

    def _schedule(self):
        """The day as the phone shows it. Edit schedule.json; nothing else to touch."""
        try:
            with open(os.path.join(HERE, "schedule.json"), encoding="utf-8") as f:
                data = json.load(f)
        except Exception as e:
            log.warning("vipa: schedule.json: %s", e)
            data = {"items": [], "note": ""}
        items = [i for i in data.get("items", []) if i.get("at") and i.get("title")]
        items.sort(key=lambda i: i["at"])
        return {"items": items, "note": data.get("note", ""), "ts": int(time.time() * 1000)}

    def _reset(self):
        with self._lock:
            del self._out[:]
            self._seq = 0

    # ---------------------------------------------------------------- inbound

    def _deliver(self, text, user="vipa"):
        """Queue one message. The HTTP response never waits for the agent."""
        self._push("user", text)
        if self.inject_rules and not text.startswith("/"):
            # Slash commands (/new, /reset, ...) must reach the gateway untouched.
            text = RULES + "\n\n" + text
        source = self.build_source(
            chat_id=self.chat_id,
            chat_name="vipa",
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

    # Notices the gateway emits about itself. They are not for the phone.
    NOISE = ("Self-improvement review", "Gateway online", "Memory updated",
             "Redirected current run", "\u21aa", "no assistant output for this turn")

    @classmethod
    def is_noise(cls, text):
        t = (text or "").strip()
        return t.startswith("[interrupt") or any(n in t for n in cls.NOISE)

    async def send(self, chat_id, content, reply_to=None, metadata=None):
        text = condense(content)
        if not text:
            return SendResult(success=False)
        if self.is_noise(text):
            log.info("vipa: dropped system notice: %s", text[:60])
            return SendResult(success=True, message_id="dropped")
        return SendResult(success=True, message_id=str(self._push("assistant", text)))

    async def send_typing(self, chat_id):
        return None

    async def get_chat_info(self, chat_id):
        return {"name": "vipa", "type": "dm"}

    # ---------------------------------------------------------------- speech

    def _transcribe(self, audio, content_type):
        import urllib.request

        boundary = "----vipa%d" % int(time.time() * 1000)
        head = ("--%s\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n%s\r\n"
                "--%s\r\nContent-Disposition: form-data; name=\"file\"; "
                "filename=\"rec.m4a\"\r\nContent-Type: %s\r\n\r\n"
                % (boundary, STT_MODEL, boundary, content_type)).encode()
        tail = ("\r\n--%s--\r\n" % boundary).encode()

        req = urllib.request.Request(
            GROQ_URL, data=head + audio + tail,
            headers={"Authorization": "Bearer " + self.groq_key,
                     "User-Agent": "vipa/1.0",   # without it Cloudflare answers 403
                     "Content-Type": "multipart/form-data; boundary=" + boundary})
        with urllib.request.urlopen(req, timeout=90, context=_ssl_context()) as r:
            return json.load(r).get("text", "").strip()

    # ---------------------------------------------------------------- http

    def _make_handler(self):
        adapter = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args):
                return

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

            def do_OPTIONS(self):
                self._send(204, {})

            def do_GET(self):
                path, _, query = self.path.partition("?")
                if path == "/api/health":
                    self._send(200, {"ok": True, "seq": adapter._seq,
                                     "stt": bool(adapter.groq_key)})
                elif path == "/api/day":
                    day = None
                    for kv in query.split("&"):
                        k, _, v = kv.partition("=")
                        if k == "date":
                            day = v
                    self._send(200, read_day(day))
                elif path == "/api/days":
                    n = 30
                    for kv in query.split("&"):
                        k, _, v = kv.partition("=")
                        if k == "n":
                            try:
                                n = int(v)
                            except ValueError:
                                pass
                    self._send(200, {"weights": weights(n)})
                elif path == "/api/schedule":
                    self._send(200, adapter._schedule())
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

                if path == "/api/push":
                    raw = self._body()
                    try:
                        text = condense(json.loads(raw.decode() or "{}").get("text") or "")
                    except Exception:
                        self._send(400, {"error": "bad json"})
                        return
                    if not text:
                        self._send(400, {"error": "empty"})
                        return
                    if adapter.is_noise(text):
                        log.info("vipa: dropped system notice (push): %s", text[:60])
                        self._send(200, {"ok": True, "dropped": True})
                        return
                    adapter._push("assistant", text)
                    self._send(202, {"ok": True})
                    return

                if path == "/api/day":
                    raw = self._body()
                    try:
                        body = json.loads(raw.decode() or "{}")
                    except Exception:
                        self._send(400, {"error": "bad json"})
                        return
                    kind = (body.get("kind") or "").strip()
                    if kind not in KINDS:
                        self._send(400, {"error": "kind must be one of " + "|".join(KINDS)})
                        return
                    try:
                        entry = log_entry(kind, (body.get("text") or "").strip(),
                                          body.get("kcal"), body.get("kg"), body.get("ts"))
                    except Exception as e:
                        self._send(400, {"error": str(e)})
                        return
                    self._send(200, {"ok": True, "entry": entry})
                    return

                if path == "/api/reset":
                    adapter._reset()
                    self._send(200, {"ok": True})
                    return

                if path == "/api/voice":
                    if not adapter.groq_key:
                        self._send(503, {"error": "GROQ_API_KEY is not set; the phone cannot send voice"})
                        return
                    audio = self._body()
                    if len(audio) < 2000:
                        self._send(400, {"error": "too small"})
                        return
                    ctype = self.headers.get("Content-Type") or "audio/mp4"
                    try:
                        text = adapter._transcribe(audio, ctype)
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


HERE = os.path.dirname(os.path.abspath(__file__))
DAYS = os.path.join(os.environ.get("HERMES_HOME", os.path.expanduser("~/.hermes")), "vipa", "days")


def _ssl_context():
    """Find a CA bundle. Works where certifi is absent (Hermes' own Python has none)."""
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


def _read(name):
    try:
        with open(os.path.join(HERE, name), encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


def _routine():
    """The user's day, as the phone shows it, for the agent to reason with."""
    try:
        data = json.loads(_read("schedule.json") or "{}")
    except Exception:
        return ""
    items = [i for i in data.get("items", []) if i.get("at") and i.get("title")]
    if not items:
        return ""
    items.sort(key=lambda i: i["at"])
    lines = ["## The user's routine (fixed times, every day)", ""]
    for i in items:
        line = "%s %s" % (i["at"], i["title"])
        if i.get("note"):
            line += " — " + i["note"]
        lines.append("- " + line)
    lines += ["", "Use it when asked what the user is doing now, or what comes next. "
                  "It is not a schedule to manage: never create, move or cancel anything in it."]
    return "\n".join(lines)


def platform_hint() -> str:
    """philosophy.md, criteria.md and the routine enter every vipa turn."""
    parts = [_read("philosophy.md"), _read("criteria.md"), _routine()]
    text = "\n\n".join(p for p in parts if p)
    return text or ("vipa is a tiny flip phone (KYF39): 480x854, no touchscreen, four keys. "
                    "Answer in one short line. No emoji. Only what is actually owed today.")


def _rules_id():
    """Short digest of philosophy.md + criteria.md, stable across restarts."""
    import hashlib
    h = hashlib.sha256()
    # the adapter too: a new tool or behaviour needs a session that knows about it
    for name in ("philosophy.md", "criteria.md", "schedule.json", "adapter.py"):
        try:
            with open(os.path.join(HERE, name), "rb") as f:
                h.update(f.read())
        except OSError:
            pass
    return h.hexdigest()[:6]


def _endpoint(config, path):
    listen = (extra_or_secret(config.extra if config else {}, "listen", "VIPA_LISTEN")
              or "0.0.0.0:8099")
    _, _, port = listen.partition(":")
    return "http://127.0.0.1:%s%s" % (port or "8099", path)


async def _standalone_send(pconfig, chat_id, message, *, thread_id=None, media_files=None,
                           force_document=False):
    """Out-of-process delivery (standalone_sender_fn): cron, `hermes send`, scripts.

    No adapter, no agent — the plugin's own HTTP port takes the line and the phone picks
    it up on its next poll.
    """
    import urllib.request

    text = condense(message)
    if not text:
        return {"success": False, "error": "empty message"}

    def post():
        body = json.dumps({"text": text}, ensure_ascii=False).encode()
        req = urllib.request.Request(_endpoint(pconfig, "/api/push"), data=body,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status

    try:
        status = await asyncio.get_event_loop().run_in_executor(None, post)
        if status >= 400:
            return {"success": False, "error": "vipa push %s" % status}
    except Exception as e:
        return {"success": False, "error": "vipa push failed: %s" % e}
    return {"success": True, "platform": "vipa", "chat_id": chat_id or "vipa"}


def check_requirements() -> bool:
    return True


def register(ctx):
    _hint = platform_hint()
    log.info("vipa: platform hint %d chars", len(_hint))
    kwargs = dict(
        name="vipa",
        label="vipa",
        adapter_factory=VipaAdapter,
        check_fn=check_requirements,
        required_env=[],
        max_message_length=2000,
        platform_hint=_hint,
    )
    # One phone, at home. No pairing prompt.
    kwargs["standalone_sender_fn"] = _standalone_send

    ctx.register_tool(
        name="vipa_log",
        toolset="hermes-vipa",
        schema={
            "name": "vipa_log",
            "description": ("Record what the user's body reports today: weight, a meal with "
                            "estimated kcal, mood, sleep, steps, or another health note. "
                            "Call it in the same turn the user tells you; do not promise it."),
            "parameters": {
                "type": "object",
                "properties": {
                    "kind": {"type": "string", "enum": list(KINDS)},
                    "text": {"type": "string", "description": "what was eaten / how it felt"},
                    "kcal": {"type": "number", "description": "estimated calories, meals only"},
                    "kg": {"type": "number", "description": "body weight"},
                    "ts": {"type": "string", "description": "HH:MM; omit for now"},
                },
                "required": ["kind"],
            },
        },
        handler=_tool_log,
        emoji="\U0001f9b7",
    )

    ctx.register_tool(
        name="vipa_day",
        toolset="hermes-vipa",
        schema={
            "name": "vipa_day",
            "description": ("Read what the user's body recorded on a day: weight, meals with kcal, "
                            "mood, sleep, steps. Use it whenever the user asks about "
                            "yesterday or any past day, or wants a summary over time."),
            "parameters": {
                "type": "object",
                "properties": {
                    "date": {"type": "string", "description": "YYYY-MM-DD; omit for today"},
                },
            },
        },
        handler=_tool_day,
        emoji="\U0001f4c4",
    )
    # Cron: `deliver=vipa` resolves the home channel from this env var.
    kwargs["cron_deliver_env_var"] = "VIPA_HOME_CHANNEL"
    kwargs["allowed_users_env"] = "VIPA_ALLOWED_USERS"
    kwargs["allow_all_env"] = "VIPA_ALLOW_ALL_USERS"
    try:
        ctx.register_platform(**kwargs)
    except TypeError:
        for key in ("allowed_users_env", "allow_all_env", "standalone_sender_fn",
                    "cron_deliver_env_var"):
            kwargs.pop(key, None)
        ctx.register_platform(**kwargs)
