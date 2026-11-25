#!/usr/bin/env bash
# vipa installer。何度実行しても壊れない。
set -euo pipefail

REPO="$(cd "$(dirname "$0")" && pwd)"
H="${HERMES_HOME:-$HOME/.hermes}"
SRC="$REPO/hermes-plugin/vipa"
DST="$H/plugins/vipa"
PORT="${VIPA_PORT:-8099}"

say() { printf '  %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v hermes >/dev/null 2>&1 || die "hermes が無い"
[ -d "$H" ] || die "$H が無い"

# 1. プラグイン
mkdir -p "$H/plugins"
rm -rf "$DST"
cp -R "$SRC" "$DST"
say "plugin -> $DST"

# 2. 秘密（任意）
if [ ! -f "$H/.env" ]; then
  printf 'VIPA_LISTEN=0.0.0.0:%s\n' "$PORT" > "$H/.env"
  chmod 600 "$H/.env"
  say "created $H/.env"
fi

# 3. 有効化
hermes plugins enable vipa >/dev/null 2>&1 || say "plugins enable: 失敗（手で hermes plugins enable vipa）"
say "enabled"

# 4. 待受
up() { curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1; }
if ! up; then
  nohup hermes gateway start >/dev/null 2>&1 &
  for _ in $(seq 1 20); do up && break; sleep 1; done
fi
up || die "$PORT が応答しない。'hermes gateway status' と 'hermes gateway logs' を見る"
say "server: $PORT ok"

# 5. 端末（挿さっていれば）
if command -v adb >/dev/null 2>&1 && adb devices | awk 'NR>1 && $2=="device"' | grep -q .; then
  adb install -r "$REPO/app/vipaos.apk" >/dev/null && say "APK を投入"
  URL="${VIPA_URL:-http://$(ipconfig getifaddr en0 2>/dev/null || echo 127.0.0.1):$PORT}"
  printf '%s\n' "$URL" > /tmp/vipa.url
  adb push /tmp/vipa.url /sdcard/vipaos.url >/dev/null && say "接続先 -> $URL"
  adb shell am start -n os.vipa.kiosk/.MainActivity >/dev/null 2>&1 || true
else
  say "端末が挿さっていない（USB で挿して、もう一度実行）"
fi

say "完了"
