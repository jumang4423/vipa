# INSTALL

Read this before running anything. Written for an agent doing the install; a human can
follow it too.

## What gets created

| | |
|---|---|
| `~/.hermes/plugins/vipa/` | the plugin (`adapter.py`, stdlib only, no pip installs) |
| `~/.hermes/config.yaml` | `platforms.vipa.*` added; backup written next to it |
| `~/.hermes/.env` | `VIPA_LISTEN`, `VIPA_ALLOW_ALL_USERS` (and `GROQ_API_KEY` if provided) |
| `/sdcard/vipa.url` on the phone | the server URL the app should talk to |
| `/tmp/vipa-install.txt` | `ok` or the tail of the gateway log |

The plugin *is* the server. There is no second process and no extra port besides the one
plugin port (8099 by default, 8098… if taken).

## Run

```bash
git clone https://github.com/jumang4423/vipa.git ~/vipa 2>/dev/null || git -C ~/vipa pull -q
cd ~/vipa && VIPA_NGROK=1 ./install.sh
```

`VIPA_NGROK=1` is optional. Without it the phone talks to the Mac's LAN address, which is
all you need at home.

`install.sh` never restarts anything. When the plugin code changed it prints
`NEED: run ./restart.sh`, and that one must run outside the agent session: Hermes refuses,
from inside a gateway session, any command that could stop the gateway — and it judges a
whole script by what it contains. Keeping the restart in a separate file is what lets the
agent run `install.sh` at all.

```bash
./restart.sh            # 2s later, detached; result in /tmp/vipa-restart.txt
```

It restarts through launchd when the plist exists, else through the CLI, then waits for
`/api/health` and writes `ok` or the tail of the gateway log.

**Do not work around the guard.** `osascript ... Terminal`, `at`, `launchctl` from inside the
session, backgrounding it — all of it either gets blocked as well or kills the turn that is
trying it. The guard exists because the restart kills whatever is running in the gateway,
including the agent. Report the one line and stop.

A restart only happens when the plugin code actually changed (`.vipa-adapter.sha`);
re-running the installer is otherwise a no-op for the gateway.

## Output contract

The installer prints exactly two kinds of lines:

- `NEXT:` — the one thing to tell the human.
- `NEED:` — something only a human can do (connect the phone, provide a Groq key, verify
  the ngrok account). Relay these verbatim, one line each.

Relay those lines only. Do not summarise diffs, do not list what was copied.

## Understanding the phone

- `POST /api/voice` — recorded audio (`audio/mp4`). Transcribed by Groq, then queued for the
  agent. **The phone has no usable keyboard, so this is the only real input path.** No
  `GROQ_API_KEY` means the phone cannot send anything at all: `/api/voice` returns `503`.
- `POST /api/in` — `{"text":"..."}`. For curl and tests; the phone does not use it.
- `GET /api/out?since=N` — `{"messages":[{seq,role,text,ts}],"last":N}`. The phone polls every
  5 seconds. If `since` is ahead of the counter (gateway restarted) the box resyncs from 0.
- `POST /api/reset` — empties the outbox.
- `GET /api/health` — `{"ok":true,"seq":N,"stt":true|false}`. `stt:false` means no Groq key.

Inbound is acknowledged with `202` immediately; turns run on a serial queue. There is no
push channel: the phone has no GMS.

## How the rules reach the agent

`philosophy.md` and `criteria.md` are read by `register()` and handed to Hermes as
`platform_hint`, which lands in the system prompt of every turn on this platform. Verified:
with a fresh session the agent answers "about ten days, and never send counts, streaks or
scores" — that text only exists in those two files.

One catch: **a session keeps the system prompt it was born with.** The prompt is stored in
`state.db.system_prompts` and reused by session, so editing the files afterwards changes
nothing until a new session starts. Two ways to get one:

```
# a brand new chat (also keeps the old history intact)
echo 'VIPA_CHAT=vipa2' >> ~/.hermes/.env

# or, from the phone
/new
```

If you cannot start a new session, `VIPA_INJECT_RULES=1` also carries a condensed version of
the rules on every inbound message. Off by default; it is a fallback, not the design.

## Optional: no approval prompts

```yaml
approvals:
  mode: "off"                 # same as --yolo
  cron_mode: approve
  single_query_mode: approve
  unattended_mode: approve
  destructive_slash_confirm: false
  mcp_reload_confirm: false
```

With `mode: off` the agent runs dangerous commands without asking. Do this only on a machine
you are willing to hand to it. One guard stays regardless: it cannot restart or stop its own
gateway (that would kill the turn doing it), which is why `install.sh` hands you `restart.sh`.

Set it with the CLI, not by hand — the key is top-level:

```bash
hermes config set approvals.mode off
```

## Settings (`~/.hermes/.env`)

```
VIPA_LISTEN=0.0.0.0:8099
VIPA_ALLOW_ALL_USERS=1                       # no pairing prompt
GROQ_API_KEY=gsk_...                         # required for input
VIPA_NGROK_URL=your-name.ngrok-free.dev      # stable ngrok URL
```

## Outside access with ngrok

```bash
ngrok http 8099
```

Put the URL on the phone:

```bash
echo 'https://xxxx.ngrok-free.app' > /tmp/vipa.url
adb push /tmp/vipa.url /sdcard/vipa.url
adb shell am start -n os.vipa.kiosk/.MainActivity
```

Known traps:

- the ngrok account must have a verified email, or the agent refuses to start (`ERR_NGROK_123`);
- the ngrok agent must be recent (`ERR_NGROK_121`) — `brew upgrade ngrok`;
- free URLs change on every restart. A free "dev domain" is stable: put it in
  `VIPA_NGROK_URL` and pass `--url=` (the installer does this for you).

## Known limits of the phone

- Android 5.1, no GMS, no Play Services. Polling only.
- Its CA store is from 2015, so Let's Encrypt (ngrok) certificates fail verification.
  `Tls.java` relaxes verification **for the configured server host only**; hostname checks
  stay on. Keep that in mind before pointing the phone at anything else.
- No touchscreen. Four keys: left soft key records/stops, lower keys jump to the end, the
  D-pad scrolls, the centre key pages.
- 480×854 at 240dpi, system font scale 1.45 — the page sets `textZoom(100)`.

## Rebuilding the app

Only needed if you change `app/`. The prebuilt `app/vipa.apk` is committed.

```bash
SDK="$HOME/Library/Android/sdk"; BT="$SDK/build-tools/34.0.0"; AJ="$SDK/platforms/android-34/android.jar"
P=app; KS="$HOME/vipa/debug.keystore"   # make one: keytool -genkeypair -keystore $KS -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
javac --release 8 -cp "$AJ" -d "$P/build/classes" $(find "$P/src" -name '*.java')
"$BT/d8" --min-api 21 --lib "$AJ" --output "$P/build/dex" $(find "$P/build/classes" -name '*.class')
"$BT/aapt2" link -o "$P/build/base.apk" -I "$AJ" --manifest "$P/AndroidManifest.xml" \
  -A "$P/src/os/vipa/kiosk/assets" --min-sdk-version 21 --target-sdk-version 22
cp "$P/build/base.apk" "$P/build/unsigned.apk"
(cd "$P/build/dex" && zip -q -j "../unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$P/build/unsigned.apk" "$P/build/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$P/out/vipa.apk" "$P/build/aligned.apk"
cp "$P/out/vipa.apk" app/vipa.apk
```

The committed `app/vipa.apk` is signed with a throwaway debug key. Installing it is fine, but
rebuilding with a different key means Android will refuse to update the installed app —
uninstall the old one first (`adb uninstall os.vipa.kiosk`).

## Removing it

```bash
hermes plugins disable vipa
rm -rf ~/.hermes/plugins/vipa
launchctl kickstart -k gui/$(id -u)/ai.hermes.gateway
adb uninstall os.vipa.kiosk
adb shell rm -f /sdcard/vipa.url
```

Also delete the `platforms.vipa` block from `config.yaml` and the `VIPA_*` / `GROQ_API_KEY`
lines you added to `.env`.
