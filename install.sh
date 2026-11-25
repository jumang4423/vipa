#!/usr/bin/env bash
# vipa installer
# エージェントに実行させる前提。何度実行しても壊れない。
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
PLUGIN_SRC="$REPO_DIR/hermes-plugin/vipa"
PLUGIN_DST="$HERMES_HOME/plugins/vipa"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# 1. Hermes があるか
command -v hermes >/dev/null 2>&1 || die "hermes が無い。先に Hermes を入れる"
[ -d "$HERMES_HOME" ] || die "$HERMES_HOME が無い"

# 2. プラグインを置く
mkdir -p "$HERMES_HOME/plugins"
rm -rf "$PLUGIN_DST"
cp -R "$PLUGIN_SRC" "$PLUGIN_DST"
say "plugin -> $PLUGIN_DST"

# 3. 秘密と待受
ENV_FILE="$HERMES_HOME/.env"
touch "$ENV_FILE"; chmod 600 "$ENV_FILE"
if ! grep -q '^VIPA_SECRET=' "$ENV_FILE"; then
  SECRET="$(python3 -c 'import secrets;print(secrets.token_hex(16))')"
  printf 'VIPA_SECRET=%s\n' "$SECRET" >> "$ENV_FILE"
  say "VIPA_SECRET を作成"
fi
grep -q '^VIPA_LISTEN=' "$ENV_FILE" || echo 'VIPA_LISTEN=0.0.0.0:8099' >> "$ENV_FILE"

# 4. 有効化して起動
hermes plugins enable vipa >/dev/null 2>&1 || true
hermes gateway start >/dev/null 2>&1 || true

# 5. 生きているか
sleep 2
curl -fsS http://127.0.0.1:8099/api/health >/dev/null 2>&1 \
  || die "8099 が応答しない。'hermes gateway status' と 'hermes gateway logs' を見る"
say "server: 8099 ok"

# 6. 端末が挿さっていれば入れる
if command -v adb >/dev/null 2>&1; then
  if adb devices | grep -qw device; then
    adb install -r "$REPO_DIR/app/vipaos.apk" >/dev/null && say "APK を端末に投入"
    LAN_IP="$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1)"
    URL="${VIPA_URL:-http://$LAN_IP:8099}"
    printf '%s\n' "$URL" > /tmp/vipa.url
    adb push /tmp/vipa.url /sdcard/vipaos.url >/dev/null && say "端末の接続先 -> $URL"
    adb shell am start -n os.vipa.kiosk/.MainActivity >/dev/null 2>&1 || true
  else
    say "端末が挿さっていない（後で USB で挿して、もう一度実行）"
  fi
fi

say "完了"
echo "外から使うなら: ngrok http 8099 && adb push でURLを差し替え"
