# vipa

Turn a plain flip phone (KYF39) into a terminal that **does nothing on its own**.

## Why

A smartphone is a door that opens onto everything. Every feed, every notification, every
shop, every argument — all of it arrives whether you asked or not.

vipa is the opposite. The phone shows one screen and nothing else. When you have nothing to
say, it stays dark. The judgment about what deserves attention does not live in your pocket;
it lives in the machine at home, where you can read it, edit it and turn it off.

The phone is a door, not a world.

## Requirements

| | |
|---|---|
| **Hermes** | the agent, on a machine that stays on (your Mac is fine) |
| **KYF39** | the phone app, USB once to install |
| **Groq API key** | only if you want to talk to it (voice → text) |
| **ngrok** | only if you want to reach it from outside the house |

Nothing else. No extra server: the plugin *is* the server.

## Install

Tell Hermes:

```
https://github.com/jumang4423/vipa — read it and run install.sh to the end.
```

Or run it yourself:

```bash
git clone https://github.com/jumang4423/vipa.git ~/vipa && cd ~/vipa
./install.sh                       # home network only
VIPA_NGROK=1 ./install.sh           # also reachable from outside
```

`install.sh` does everything:

1. copies the plugin into `~/.hermes/plugins/`
2. `hermes plugins enable vipa`
3. writes `platforms.vipa` into `config.yaml` (backup first, other platforms untouched)
4. picks a free port (8099, else 8098…) and records it as `VIPA_LISTEN`
5. restarts the gateway **detached**, 15s later, so the caller's turn can finish
6. with a phone on USB: installs the APK, writes the server URL to `/sdcard/vipaos.url`, launches it
7. with `VIPA_NGROK=1`: opens an ngrok tunnel and writes that URL to the phone

It ends with one `NEXT:` line and `NEED:` lines for what only a human can do.

## Endpoints

| | |
|---|---|
| `POST /api/voice` | audio → Groq → agent |
| `POST /api/in` | `{"text":"..."}` → agent |
| `GET /api/out?since=N` | new messages; the phone polls every 5s |
| `POST /api/reset` | empty the outbox |
| `GET /api/health` | liveness |

Inbound is acknowledged at once (`202`); the turn runs behind a serial queue and the reply
lands in the outbox. No push: this phone has no GMS.

## Folders

| | |
|---|---|
| `app/` | the phone app (Java, `assets/index.html`, prebuilt APK) |
| `hermes-plugin/vipa/` | the Hermes plugin — `adapter.py`, stdlib only |
| `install.sh` | the installer |

## Optional settings (`~/.hermes/.env`)

```
VIPA_LISTEN=0.0.0.0:8099
VIPA_ALLOW_ALL_USERS=1                            # no pairing prompt
GROQ_API_KEY=...                                  # voice only
VIPA_NGROK_URL=your-name.ngrok-free.dev           # stable ngrok URL
```

## Limits

- No touchscreen: four keys. Left soft key records/stops, lower keys jump to the bottom, the D-pad scrolls.
- 480×854. Keep replies short.
- Android 5.1, no GMS: polling only. It also lacks any CA newer than 2015, so `Tls.java`
  accepts the certificate for **your own server host only**.
- One day of battery.

## License

MIT
