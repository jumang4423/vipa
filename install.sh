#!/usr/bin/env bash
# vipa installer. Standalone and idempotent.
#
#   ./install.sh              home network only
#   VIPA_NGROK=1 ./install.sh also reachable from outside (ngrok)
#
# Ends with one NEXT line and, when needed, NEED lines for a human.
set -euo pipefail

REPO="$(cd "$(dirname "$0")" && pwd)"
H="${HERMES_HOME:-$HOME/.hermes}"
CFG="$H/config.yaml"
ENV="$H/.env"
SRC="$REPO/hermes-plugin/vipa"
DST="$H/plugins/vipa"

say()  { printf '  %s\n' "$*"; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }
health()  { curl -fsS -m 2 "http://127.0.0.1:$1/api/health" 2>/dev/null | grep -q '"ok": true'; }
vipa_up() { curl -fsS -m 2 "http://127.0.0.1:$1/api/health" 2>/dev/null | grep -q '"seq"'; }
taken()   { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | grep -q .; }

tunnel_url() {  # $1 = local port; prints the https url of a tunnel pointing at it
  _tmp="$(mktemp)"
  curl -s -m 2 http://127.0.0.1:4040/api/tunnels > "$_tmp" 2>/dev/null || true
  python3 -c '
import json, sys
try:
    for x in json.load(open(sys.argv[1])).get("tunnels", []):
        addr = x.get("config", {}).get("addr", "")
        if addr.endswith(":" + sys.argv[2]) and x["public_url"].startswith("https"):
            print(x["public_url"])
            break
except Exception:
    pass
' "$_tmp" "$1"
  rm -f "$_tmp"
}

command -v hermes >/dev/null 2>&1 || die "hermes not found"
[ -d "$H" ] || die "$H not found"

# ---------------------------------------------------------------- 1. plugin
mkdir -p "$H/plugins"
NEW_SHA="$(shasum -a 256 "$SRC/adapter.py" | cut -d' ' -f1)"
OLD_SHA="$(shasum -a 256 "$DST/adapter.py" 2>/dev/null | cut -d' ' -f1 || true)"
rm -rf "$DST"
cp -R "$SRC" "$DST"
say "plugin -> $DST"

hermes plugins enable vipa >/dev/null 2>&1 || say "hermes plugins enable vipa failed (run it by hand)"
say "plugin enabled"

# ---------------------------------------------------------------- 2. port
PORT=""
for p in ${VIPA_PORTS:-8099 8098 8097 8096 8095}; do
  if ! taken "$p" || health "$p"; then PORT="$p"; break; fi
done
[ -n "$PORT" ] || die "no free port in 8095-8099"
[ "$PORT" = "8099" ] || say "8099 taken -> using $PORT"

touch "$ENV"; chmod 600 "$ENV"
if grep -q '^VIPA_LISTEN=' "$ENV"; then
  sed -i.bak "s|^VIPA_LISTEN=.*|VIPA_LISTEN=0.0.0.0:$PORT|" "$ENV"; rm -f "$ENV.bak"
else
  printf 'VIPA_LISTEN=0.0.0.0:%s\n' "$PORT" >> "$ENV"
fi
grep -q '^VIPA_ALLOW_ALL_USERS=' "$ENV" || printf 'VIPA_ALLOW_ALL_USERS=1\n' >> "$ENV"

say "listen -> 0.0.0.0:${PORT} (no pairing)"

# ---------------------------------------------------------------- 3. config
[ -f "$CFG" ] || die "$CFG not found"
cp "$CFG" "$CFG.bak.$(date +%s)"
python3 - "$CFG" <<'PY'
import re, sys

BLOCK = (
    "platforms:\n"
    "  vipa:\n"
    "    enabled: true\n"
    "    gateway_restart_notification: false\n"
    "    home_channel:\n"
    "      platform: vipa\n"
    "      chat_id: vipa\n"
    "      name: vipa\n"
    "      user_id: vipa\n"
)


def ensure_inside(text):
    """Add the vipa keys under an existing `  vipa:` block."""
    if not re.search(r'^    gateway_restart_notification: false', text, re.M):
        text = re.sub(r'^(  vipa:[ \t]*\n    enabled: true[ \t]*)$',
                      r'\1\n    gateway_restart_notification: false',
                      text, count=1, flags=re.M)
    if not re.search(r'^      chat_id: vipa', text, re.M):
        text = re.sub(r'^(  vipa:[ \t]*\n    enabled: true[ \t]*\n(?:    [a-z_]+: [^\n]*\n)*)',
                      r'\1    home_channel:\n      platform: vipa\n      chat_id: vipa\n'
                      r'      name: vipa\n      user_id: vipa\n',
                      text, count=1, flags=re.M)
    return text


p = sys.argv[1]
s = open(p, encoding="utf-8").read()

if re.search(r'^[ \t]+vipa:[ \t]*$', s, re.M):
    open(p, "w", encoding="utf-8").write(ensure_inside(s))
    print("  config: vipa already present")
elif re.search(r'^platforms:[ \t]*$', s, re.M):
    s = re.sub(r'^platforms:[ \t]*$', BLOCK.rstrip("\n"), s, count=1, flags=re.M)
    open(p, "w", encoding="utf-8").write(s)
    print("  config: inserted under platforms")
else:
    if not s.endswith("\n"):
        s += "\n"
    open(p, "w", encoding="utf-8").write(s + "\n" + BLOCK)
    print("  config: appended platforms")
PY

# 3c. the plugin's tools live in the `hermes-vipa` toolset. That name is also the implicit
# default toolset for a plugin platform, so registering into it would shadow every built-in
# tool (web search, browser, terminal). Keep the platform's toolsets explicit.
python3 - "$CFG" <<'PY3'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
if re.search(r'^platform_toolsets:[ \t]*$', s, re.M):
    if not re.search(r'^  vipa:', s, re.M):
        s = re.sub(r'^platform_toolsets:[ \t]*$',
                   "platform_toolsets:\n  vipa: [hermes-cli, hermes-vipa]", s, count=1, flags=re.M)
        open(p, "w", encoding="utf-8").write(s)
        print("  config: platform_toolsets.vipa set")
    else:
        print("  config: platform_toolsets.vipa already present")
else:
    if not s.endswith("\n"):
        s += "\n"
    s += "\nplatform_toolsets:\n  vipa: [hermes-cli, hermes-vipa]\n"
    open(p, "w", encoding="utf-8").write(s)
    print("  config: platform_toolsets appended")
PY3

# 3d. quiet on the phone. Hermes pushes tool progress and "⏳ Working" heartbeats to every
# messaging platform by default; on a 480px screen they arrive instead of the answer.
python3 - "$CFG" <<'PY4'
import re, sys

p = sys.argv[1]
s = open(p, encoding="utf-8").read()
wanted = """      tool_progress: off
      long_running_notifications: false
      busy_ack_detail: false
      interim_assistant_messages: false
"""
if re.search(r'^      long_running_notifications:', s, re.M):
    print("  config: display.platforms.vipa already quiet")
elif re.search(r'^  platforms:[ \t]*$', s, re.M):
    s = re.sub(r'^  platforms:[ \t]*$', "  platforms:\n    vipa:\n" + wanted.rstrip("\n"),
               s, count=1, flags=re.M)
    open(p, "w", encoding="utf-8").write(s)
    print("  config: display.platforms.vipa added")
elif re.search(r'^display:[ \t]*$', s, re.M):
    s = re.sub(r'^display:[ \t]*$',
               "display:\n  platforms:\n    vipa:\n" + wanted.rstrip("\n"), s, count=1, flags=re.M)
    open(p, "w", encoding="utf-8").write(s)
    print("  config: display block added")
else:
    if not s.endswith("\n"):
        s += "\n"
    s += "\ndisplay:\n  platforms:\n    vipa:\n" + wanted
    open(p, "w", encoding="utf-8").write(s)
    print("  config: display appended")
PY4

# 3e. web_search needs one backend; DuckDuckGo's ddgs needs no key.
PYV="$(ls -d "$H"/installs/*/environments/*/venv/bin/python 2>/dev/null | tail -1 || true)"
if [ -n "$PYV" ] && command -v uv >/dev/null 2>&1; then
  if "$PYV" -c "import ddgs" >/dev/null 2>&1; then
    say "web search: ddgs already present"
  else
    uv pip install --python "$PYV" ddgs >/dev/null 2>&1 \
      && say "web search: ddgs installed" || say "web search: ddgs install failed (uv pip install ddgs)"
  fi
else
  say "web search: skipped (uv or the gateway venv not found)"
fi

# 3f. cron notifications. The shared cron prompt offers the agent an exit — "if there is
# genuinely nothing new to report, answer [SILENT]" — so a one-line reminder written without
# the prohibition disappears with delivery_outcome=suppressed and the phone shows nothing.
# The adapter strips the cron wrapper (Cronjob Response: / job_id / footer); this writes the
# prompt rule into the agent's memory so every job it creates carries it.
mkdir -p "$H/memories" 2>/dev/null && chmod 700 "$H/memories" 2>/dev/null || true
python3 - "$H/memories/MEMORY.md" <<'PY4B'
import os, sys

p = sys.argv[1]
rule = ("vipa への cron 通知ジョブは、プロンプトに必ず「[SILENT]は絶対に使わない、必ず本文を返す」"
        "と書く。Hermes の共通 cron 指示が「報告事項なし → [SILENT] で配送停止」を含むため、"
        "短い通知をそのまま書くと届かずに消える（executions.db の delivery_outcome=suppressed）。"
        "時報系は実行時刻と本文の時刻を合わせる。")
try:
    s = open(p, encoding="utf-8").read()
except FileNotFoundError:
    s = ""
if "[SILENT]は絶対に使わない" in s:
    print("  memory: cron rule already present")
else:
    if s and not s.endswith("\n"):
        s += "\n"
    s += ("\u00a7\n" if s.strip() else "") + rule + "\n"
    open(p, "w", encoding="utf-8").write(s)
    os.chmod(p, 0o600)
    print("  memory: cron notification rule added")
PY4B

# ---------------------------------------------------------------- 4. gateway
# Restarting is NOT done here on purpose: Hermes refuses to run, from inside a gateway
# session, any command that could stop the gateway — and that judgement covers this script
# as a whole if the words appear in it. So the restart lives in ./restart.sh, one line,
# which a human or an out-of-process shell runs.
FRESH=1
if vipa_up "$PORT" && [ "$NEW_SHA" = "$OLD_SHA" ]; then
  FRESH=0
  say "vipa already answers on $PORT (same code, no restart)"
elif vipa_up "$PORT"; then
  FRESH=0
  say "vipa answers on $PORT (code changed; the gateway may pick it up on its own)"
else
  # Give the running gateway a moment: it re-wires plugins when they are enabled,
  # and sometimes connects the new platform without a restart.
  for _ in $(seq 1 10); do
    vipa_up "$PORT" && { FRESH=0; say "gateway connected vipa on $PORT by itself"; break; }
    sleep 1
  done
fi

# ---------------------------------------------------------------- 5. url
URL="http://$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1):$PORT"
NGTUNNEL=""; REASON=""; NGLOG=""
if [ "${VIPA_NGROK:-0}" = "1" ]; then
  if ! command -v ngrok >/dev/null 2>&1; then
    REASON="ngrok is not installed"
  else
    NGLOG="${TMPDIR:-/tmp}/vipa-ngrok.log"
    NGURL="${VIPA_NGROK_URL:-$(grep -m1 '^VIPA_NGROK_URL=' "$ENV" 2>/dev/null | cut -d= -f2- || true)}"
    # A tunnel started from here would be a child of the caller (often the gateway),
    # and would die with it. launchd keeps it alive across restarts instead.
    NGTUNNEL="$(tunnel_url "$PORT")"
    if [ -n "${NGTUNNEL:-}" ]; then
      URL="$NGTUNNEL"
      say "ngrok already tunnelling $PORT -> $URL"
    elif [ -n "${NGURL:-}" ]; then
      nohup ngrok http --url="$NGURL" "$PORT" --log=stdout > "$NGLOG" 2>&1 &
    else
      nohup ngrok http "$PORT" --log=stdout > "$NGLOG" 2>&1 &
      for _ in $(seq 1 20); do
        NGTUNNEL="$(tunnel_url "$PORT")"
        [ -n "${NGTUNNEL:-}" ] && break
        sleep 1
      done
    fi
    if [ -n "${NGTUNNEL:-}" ]; then
      URL="$NGTUNNEL"
      say "ngrok -> $URL"
    else
      REASON=$(grep -o 'The account.*\|ERR_NGROK_[0-9]*' "$NGLOG" 2>/dev/null | head -1 | cut -c1-160)
      say "ngrok failed: ${REASON:-see $NGLOG}"
      say "staying on the LAN url: $URL"
    fi
  fi
fi

# ---------------------------------------------------------------- 6. phone
ADB_BIN="$(command -v adb 2>/dev/null || true)"
if [ -z "$ADB_BIN" ]; then
  for c in "$HOME/Library/Android/sdk/platform-tools/adb" /opt/homebrew/bin/adb /usr/local/bin/adb; do
    [ -x "$c" ] && { ADB_BIN="$c"; break; }
  done
fi
SERIAL="${VIPA_SERIAL:-}"
if [ -z "$SERIAL" ] && [ -n "$ADB_BIN" ]; then
  SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $2=="device"{print $1}')"
  N="$(printf '%s' "$SERIAL" | grep -c . || true)"
  if [ "$N" != "1" ]; then
    REAL="$(printf '%s\n' "$SERIAL" | grep -v '^emulator-' || true)"
    if [ "$(printf '%s' "$REAL" | grep -c . || true)" = "1" ]; then
      SERIAL="$REAL"; N="1"
    fi
  fi
  if [ "$N" != "1" ]; then
    say "more than one device ($N); set VIPA_SERIAL=<serial>"
    SERIAL=""
  fi
fi
if [ -n "$SERIAL" ]; then
  "$ADB_BIN" -s "$SERIAL" install -r "$REPO/app/vipa.apk" >/dev/null && say "APK installed ($SERIAL)"
  printf '%s\n' "$URL" > /tmp/vipa.url
  "$ADB_BIN" -s "$SERIAL" push /tmp/vipa.url /sdcard/vipa.url >/dev/null && say "phone -> $URL"
  # Without this the station sleeps with the screen, DNS stops resolving, and every message
  # waits until something wakes the phone. It cannot be set from inside the app.
  "$ADB_BIN" -s "$SERIAL" shell settings put global wifi_sleep_policy 2 >/dev/null 2>&1 \
    && say "phone: Wi-Fi stays on while asleep"
  "$ADB_BIN" -s "$SERIAL" shell am start -n os.vipa.kiosk/.MainActivity >/dev/null 2>&1 || true
else
  say "no phone on USB"
fi

# ---------------------------------------------------------------- 7. summary
# Everything below is meant to be relayed to a human as-is.
printf '\n'
if [ -n "$SERIAL" ]; then
  printf '  NEXT: nothing. Open the phone and talk to it.\n'
else
  printf '  NEXT: plug the KYF39 in over USB and run ./install.sh again.\n'
  printf '  NEED: connect the KYF39 over USB (allow USB debugging)\n'
fi

grep -q '^GROQ_API_KEY=.' "$ENV" 2>/dev/null || {
  printf '  NEED: GROQ_API_KEY. The phone has no usable keyboard, so voice is its only input and Groq does the transcription. Without it the phone cannot send anything. Get one at https://console.groq.com/keys, or hand it over and I will add it.\n'
}

if [ "${VIPA_NGROK:-0}" = "1" ] && [ -z "${NGTUNNEL:-}" ]; then
  if [ -n "${REASON:-}" ]; then
    printf '  NEED: ngrok could not open a tunnel. %s\n' "$REASON"
  else
    printf '  NEED: ngrok could not open a tunnel (see %s)\n' "${NGLOG:-/tmp/vipa-ngrok.log}"
  fi
  printf '  NEED: verify the ngrok account email → https://dashboard.ngrok.com/user/settings → run VIPA_NGROK=1 ./install.sh again\n'
fi

if [ "$FRESH" = "1" ]; then
  printf '  NEED: restart the gateway, or the plugin will not load. Hermes will not let me do it\n'
  printf '        from inside this session. Copy this line into a normal terminal:\n'
  printf '\n            cd %s && ./restart.sh\n\n' "$REPO"
  printf '        It restarts in 2s and writes ok to /tmp/vipa-restart.txt.\n'
fi
printf '\n  health: curl http://127.0.0.1:%s/api/health\n\n' "$PORT"
