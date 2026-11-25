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

command -v hermes >/dev/null 2>&1 || die "hermes not found"
[ -d "$H" ] || die "$H not found"

# ---------------------------------------------------------------- 1. plugin
mkdir -p "$H/plugins"
SHA_FILE="$H/.vipa-adapter.sha"
NEW_SHA="$(shasum -a 256 "$SRC/adapter.py" | cut -d' ' -f1)"
OLD_SHA="$(cat "$SHA_FILE" 2>/dev/null || true)"
rm -rf "$DST"
cp -R "$SRC" "$DST"
printf '%s\n' "$NEW_SHA" > "$SHA_FILE"
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

# 3b. the same text straight into the agent's system prompt, as a config override
python3 - "$CFG" "$DST" <<'PY2'
import os, re, sys

cfg, dst = sys.argv[1], sys.argv[2]


def read(name):
    try:
        with open(os.path.join(dst, name), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


text = "\n\n".join(p for p in (read("philosophy.md"), read("criteria.md")) if p)
lines = open(cfg, encoding="utf-8").read().splitlines()
block = ["  platform_hints:", "    vipa:", "      replace: |"]
block += ["        " + l if l.strip() else "" for l in text.splitlines()]

if any(l.strip() == "platform_hints:" for l in lines):
    print("  config: platform_hints already present (left alone)")
else:
    # find the top-level `agent:` block and insert at its end
    start = next((i for i, l in enumerate(lines) if l.rstrip() == "agent:"), None)
    if start is None:
        lines += ["", "agent:"] + block
    else:
        end = start + 1
        while end < len(lines) and (lines[end].startswith(" ") or lines[end].strip() == ""):
            if end + 1 < len(lines) and not lines[end].startswith(" ") and lines[end].strip():
                break
            end += 1
        lines[end:end] = block
    open(cfg, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("  config: platform_hints.vipa.replace written")
PY2

# 3c. the same text in SOUL.md as well. The platform hint is not always wired into the
# prompt of a gateway session, and this one has to hold: it is the agent's identity.
SOUL="$H/SOUL.md"
touch "$SOUL"
python3 - "$SOUL" "$DST" <<'PY3'
import os, sys

soul, dst = sys.argv[1], sys.argv[2]
BEGIN, END = "<!-- vipa:begin -->", "<!-- vipa:end -->"


def read(name):
    try:
        with open(os.path.join(dst, name), encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


text = "\n\n".join(p for p in (read("philosophy.md"), read("criteria.md")) if p)
if not text:
    sys.exit(0)

block = BEGIN + "\n" + text + "\n" + END + "\n"
body = open(soul, encoding="utf-8").read()

if BEGIN in body and END in body:
    head, _, rest = body.partition(BEGIN)
    _, _, tail = rest.partition(END)
    body = head.rstrip("\n") + "\n\n" + block + tail.lstrip("\n")
else:
    body = body.rstrip("\n") + "\n\n" + block

open(soul, "w", encoding="utf-8").write(body)
print("  soul: vipa block written (%d chars)" % len(body))
PY3

# ---------------------------------------------------------------- 4. gateway
# Restarting from inside a gateway session kills that session, so the restart is
# detached and delayed, and the result goes to a file.
REPORT="${VIPA_REPORT:-/tmp/vipa-install.txt}"
FRESH=1
if vipa_up "$PORT" && [ "$NEW_SHA" = "$OLD_SHA" ]; then
  FRESH=0
  say "vipa already answers on $PORT (same code, no restart)"
else
  LABEL="ai.hermes.gateway"
  if [ -f "$HOME/Library/LaunchAgents/$LABEL.plist" ]; then
    RESTART="launchctl kickstart -k gui/$(id -u)/$LABEL"
  else
    RESTART="hermes gateway stop; hermes gateway start"
  fi
  rm -f "$REPORT"
  nohup bash -c "
    sleep 15
    $RESTART >/dev/null 2>&1 || { hermes gateway stop >/dev/null 2>&1; hermes gateway start >/dev/null 2>&1; }
    for _ in \$(seq 1 40); do
      curl -fsS -m 2 http://127.0.0.1:$PORT/api/health 2>/dev/null | grep -q '\"ok\": true' \
        && { echo ok > '$REPORT'; exit 0; }
      sleep 1
    done
    { echo fail; tail -8 '$H/logs/gateway.log' 2>/dev/null; } > '$REPORT'
  " >/dev/null 2>&1 &
  say "gateway restarting in 15s (result: $REPORT)"
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
    pkill -f "ngrok http .*$PORT" 2>/dev/null || true
    if [ -n "${NGURL:-}" ]; then
      nohup ngrok http --url="$NGURL" "$PORT" --log=stdout > "$NGLOG" 2>&1 &
    else
      nohup ngrok http "$PORT" --log=stdout > "$NGLOG" 2>&1 &
    fi
    for _ in $(seq 1 20); do
      NGTUNNEL=$(curl -s -m 2 http://127.0.0.1:4040/api/tunnels 2>/dev/null \
          | python3 -c 'import json,sys; t=json.load(sys.stdin)["tunnels"]; print([x["public_url"] for x in t if x["public_url"].startswith("https")][0])' 2>/dev/null || true)
      [ -n "${NGTUNNEL:-}" ] && break
      sleep 1
    done
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

printf '\n  health: curl http://127.0.0.1:%s/api/health\n\n' "$PORT"
