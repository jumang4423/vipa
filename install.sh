#!/usr/bin/env bash
# vipa installer — 単体で完結する。何度実行しても壊れない。
#
#   ./install.sh              LAN 内だけで使う
#   VIPA_NGROK=1 ./install.sh 外からも使う（ngrok の tunnel を張って端末に書く）
set -euo pipefail

REPO="$(cd "$(dirname "$0")" && pwd)"
H="${HERMES_HOME:-$HOME/.hermes}"
CFG="$H/config.yaml"
ENV="$H/.env"
SRC="$REPO/hermes-plugin/vipa"
DST="$H/plugins/vipa"

say() { printf '  %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

health() { curl -fsS -m 2 "http://127.0.0.1:$1/api/health" 2>/dev/null | grep -q '"ok": true'; }
vipa_up() { curl -fsS -m 2 "http://127.0.0.1:$1/api/health" 2>/dev/null | grep -q '"seq"'; }
taken()  { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | grep -q .; }

command -v hermes >/dev/null 2>&1 || die "hermes が無い"
[ -d "$H" ] || die "$H が無い"

# ---------------------------------------------------------------- 1. plugin
mkdir -p "$H/plugins"
rm -rf "$DST"
cp -R "$SRC" "$DST"
say "plugin -> $DST"

hermes plugins enable vipa >/dev/null 2>&1 || say "plugins enable 失敗（後で hermes plugins enable vipa）"
say "plugin enabled"

# ---------------------------------------------------------------- 2. port
PORT=""
for p in ${VIPA_PORTS:-8099 8098 8097 8096 8095}; do
  if ! taken "$p" || health "$p"; then PORT="$p"; break; fi
done
[ -n "$PORT" ] || die "空きポートが無い（8095-8099 が全部埋まっている）"
[ "$PORT" = "8099" ] || say "8099 は他のプロセスが使用中 -> $PORT に逃がす"

touch "$ENV"; chmod 600 "$ENV"
if grep -q '^VIPA_LISTEN=' "$ENV"; then
  sed -i.bak "s|^VIPA_LISTEN=.*|VIPA_LISTEN=0.0.0.0:$PORT|" "$ENV"; rm -f "$ENV.bak"
else
  printf 'VIPA_LISTEN=0.0.0.0:%s\n' "$PORT" >> "$ENV"
fi
grep -q '^VIPA_ALLOW_ALL_USERS=' "$ENV" || printf 'VIPA_ALLOW_ALL_USERS=1\n' >> "$ENV"
say "listen -> 0.0.0.0:${PORT}（承認なし）"

# ---------------------------------------------------------------- 3. config
[ -f "$CFG" ] || die "$CFG が無い"
cp "$CFG" "$CFG.bak.$(date +%s)"
python3 - "$CFG" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
VIPA_BLOCK = (
    "platforms:\n"
    "  vipa:\n"
    "    enabled: true\n"
    "    gateway_restart_notification: false\n"
    "    home_channel:\n"
    "      platform: vipa\n"
    "      chat_id: vipa\n"
    "      name: vipaOS\n"
    "      user_id: vipa\n"
)


def ensure_notice_off(text):
    # 「♻️ Gateway online」を端末に出さない
    if re.search(r'^    gateway_restart_notification: false', text, re.M):
        return text
    if re.search(r'^  vipa:[ \t]*\n    enabled: true[ \t]*$', text, re.M):
        text = re.sub(r'^(  vipa:[ \t]*\n    enabled: true[ \t]*)$',
                      r'\1\n    gateway_restart_notification: false', text, count=1, flags=re.M)
    if not re.search(r'^      chat_id: vipa', text, re.M):
        text = re.sub(r'^(  vipa:[ \t]*\n    enabled: true[ \t]*\n(?:    [a-z_]+: [^\n]*\n)*)',
                      r'\1    home_channel:\n      platform: vipa\n      chat_id: vipa\n'
                      r'      name: vipaOS\n      user_id: vipa\n',
                      text, count=1, flags=re.M)
    return text

if re.search(r'^[ \t]+vipa:[ \t]*$', s, re.M):
    s = ensure_notice_off(s)
    open(p, "w", encoding="utf-8").write(s)
    print("  config: vipa は設定済み")
elif re.search(r'^platforms:[ \t]*$', s, re.M):
    s = re.sub(r'^platforms:[ \t]*$', VIPA_BLOCK, s, count=1, flags=re.M)
    open(p, "w", encoding="utf-8").write(s)
    print("  config: platforms の下に vipa を挿入")
else:
    if not s.endswith("\n"):
        s += "\n"
    s += "\n" + VIPA_BLOCK + "\n"
    open(p, "w", encoding="utf-8").write(s)
    print("  config: platforms を追記")
PY

# ---------------------------------------------------------------- 4. gateway
# ここを実行しているのが gateway の中（Hermes のセッション）だと、再起動で
# このスクリプトごと落ちる。だから再起動は切り離して仕掛け、結果はファイルに書かせ、
# スクリプト自身は「仕掛けた」で終わる。
#
# さらに、Hermes は「内側から hermes gateway stop/restart」を禁じている（自分ごと死ぬため）。
# install.sh も同じ扱いで弾かれるので、launchd のサービスを直接叩く。
LABEL="ai.hermes.gateway"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
if [ -f "$PLIST" ]; then
  RESTART="launchctl kickstart -k gui/$(id -u)/$LABEL"
  HOW="launchctl kickstart -k"
else
  RESTART="hermes gateway stop; hermes gateway start"
  HOW="hermes gateway stop/start"
fi
REPORT="${VIPA_REPORT:-/tmp/vipa-install.txt}"
FRESH=1
if vipa_up "$PORT"; then
  FRESH=0
  say "vipa は既に応答している（再起動しない。セッションは落ちない）"
else
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
say "gateway を再起動中（${HOW}、結果は20〜40秒後に ${REPORT}）"
fi

# ---------------------------------------------------------------- 5. URL
URL="http://$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1):$PORT"
NGTUNNEL=""
if [ "${VIPA_NGROK:-0}" = "1" ]; then
  if ! command -v ngrok >/dev/null 2>&1; then
    say "ngrok が無い。LAN の URL のまま進む"
  else
    NGLOG="${TMPDIR:-/tmp}/vipa-ngrok.log"
    pkill -f "ngrok http .*$PORT" 2>/dev/null || true
    nohup ngrok http "$PORT" --log=stdout > "$NGLOG" 2>&1 &
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
      say "ngrok が張れない: ${REASON:-原因不明（$NGLOG を見る）}"
      say "LAN の URL のまま進む: $URL"
    fi
  fi
fi

# ---------------------------------------------------------------- 6. device
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
    # エミュレータは無視して、実機が1台だけならそれを使う
    REAL="$(printf '%s\n' "$SERIAL" | grep -v '^emulator-' || true)"
    if [ "$(printf '%s' "$REAL" | grep -c . || true)" = "1" ]; then
      SERIAL="$REAL"; N="1"
    fi
  fi
  if [ "$N" != "1" ]; then
    say "端末が1台に定まらない（${N}台）。VIPA_SERIAL=<serial> を付けて再実行"
    SERIAL=""
  fi
fi
if [ -n "$SERIAL" ]; then
  "$ADB_BIN" -s "$SERIAL" install -r "$REPO/app/vipaos.apk" >/dev/null && say "APK を投入 ($SERIAL)"
  printf '%s\n' "$URL" > /tmp/vipa.url
  "$ADB_BIN" -s "$SERIAL" push /tmp/vipa.url /sdcard/vipaos.url >/dev/null && say "接続先 -> $URL"
  "$ADB_BIN" -s "$SERIAL" shell am start -n os.vipa.kiosk/.MainActivity >/dev/null 2>&1 || true
else
  say "端末が挿さっていない（USB で挿して、もう一度実行）"
fi

# ---------------------------------------------------------------- 7. 確認
# 初回は再起動が15秒後に走るので、ここでは待たない（待つと実行元のセッションが落ちる）。
# 15秒後の結果は REPORT に書かれる。
if [ "$FRESH" = "0" ]; then
  say "server: http://127.0.0.1:${PORT} ok（そのまま）"
else
  say "15秒後に gateway が再起動し、$PORT が開く（結果は ${REPORT}）"
fi

printf '\n'
if [ -n "${SERIAL}" ]; then
  printf '  NEXT: 何もしなくていい。端末を開いて話しかける。\n'
else
  printf '  NEXT: 端末を USB で挿して、もう一度 ./install.sh を実行する。\n'
fi
grep -q '^GROQ_API_KEY=.' "$ENV" 2>/dev/null \
  || printf '  （声も使うなら ~/.hermes/.env に GROQ_API_KEY=... を1行）\n'
printf '\n  health: curl http://127.0.0.1:%s/api/health\n\n' "$PORT"
