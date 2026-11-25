# vipa plugin for Hermes

Connects the KYF39 phone to Hermes. **No UI, no second server, no dependencies.**

## Endpoints

| | |
|---|---|
| `POST /api/voice` | recorded audio (`audio/mp4`) → Groq transcription → agent |
| `POST /api/in` | `{"text":"..."}` → agent |
| `GET /api/out?since=N` | new messages (the phone polls every 5s) |
| `POST /api/reset` | empty the outbox |
| `GET /api/health` | liveness |

`GET /api/out` returns `{"messages":[{seq,role,text,ts}],"last":N}`; `role` is `user` or
`assistant`. The phone feeds `last` back as `since`. If `since` is ahead of the counter
(the gateway restarted), the box resyncs from 0.

Inbound is acknowledged with `202` immediately; the turn runs on a serial queue. No push
notifications — the phone has no GMS.

No pairing prompt: `VIPA_ALLOW_ALL_USERS=1` is all it needs. `adapter.py` uses the standard
library only, so nothing has to be pip-installed into Hermes.

## Install

```bash
cp -R hermes-plugin/vipa ~/.hermes/plugins/
hermes plugins enable vipa
```

Then add the platform to `~/.hermes/config.yaml` (without this the port never opens):

```yaml
platforms:
  vipa:
    enabled: true
    gateway_restart_notification: false
```

```bash
hermes gateway restart
```

`install.sh` in the repo root does all of the above, plus the phone.

## Settings (`~/.hermes/.env`)

```
VIPA_LISTEN=0.0.0.0:8099
GROQ_API_KEY=...                                   # required (voice is the only input)
VIPA_ALLOW_ALL_USERS=1
```

## Outside access

```bash
ngrok http 8099
```

Put the URL on the phone:

```bash
echo 'https://xxxx.ngrok-free.app' > /tmp/vipa.url
adb push /tmp/vipa.url /sdcard/vipaos.url
adb shell am start -n os.vipa.kiosk/.MainActivity
```
