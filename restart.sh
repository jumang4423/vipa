#!/usr/bin/env bash
# Restart the Hermes gateway so the vipa plugin (re)loads.
#
# This is a separate file on purpose. Hermes blocks, from inside a gateway session, any
# command that could stop the gateway — and it judges a whole script by what it contains.
# With the restart out of install.sh, install.sh runs anywhere; only this one needs a shell
# outside the gateway, or a human.
#
#   ./restart.sh                 restart now, report to /tmp/vipa-restart.txt
#   VIPA_RESTART_DELAY=20 ./restart.sh
set -euo pipefail

H="${HERMES_HOME:-$HOME/.hermes}"
ENV_FILE="$H/.env"
REPORT="${VIPA_REPORT:-/tmp/vipa-restart.txt}"
DELAY="${VIPA_RESTART_DELAY:-2}"

PORT="$(grep -m1 '^VIPA_LISTEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | awk -F: '{print $NF}')"
PORT="${PORT:-8099}"

LABEL="ai.hermes.gateway"
if [ -f "$HOME/Library/LaunchAgents/$LABEL.plist" ]; then
  RESTART="launchctl kickstart -k gui/$(id -u)/$LABEL"
else
  RESTART="hermes gateway stop; hermes gateway start"
fi

rm -f "$REPORT"
nohup bash -c "
  sleep $DELAY
  $RESTART >/dev/null 2>&1 || { hermes gateway stop >/dev/null 2>&1; hermes gateway start >/dev/null 2>&1; }
  for _ in \$(seq 1 40); do
    curl -fsS -m 2 http://127.0.0.1:$PORT/api/health 2>/dev/null | grep -q '\"ok\": true' \
      && { echo ok > '$REPORT'; exit 0; }
    sleep 1
  done
  { echo fail; tail -8 '$H/logs/gateway.log' 2>/dev/null; } > '$REPORT'
" >/dev/null 2>&1 &

printf '  restarting in %ss (result: %s)\n' "$DELAY" "$REPORT"
