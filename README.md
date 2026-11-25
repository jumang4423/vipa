# vipa

A flip phone (KYF39) that carries no feed, no shop, no count — and an agent at home that
does the reading for you.

## Why

A capable tool is not a problem because you use it. It is a problem because it sits there
*available*, and every time you see it you are quietly asked why you are not using it.
Restrict it with software and you answer that question with willpower, every time, until
you run out.

So the constraint is not on what the phone can do. It is on **what reaches it**, and on
**how slowly it can be read**. The agent behind it is not limited; the door is one door,
and the screen is 480×854 with four keys. Nothing here is a moral test. Look at it for an
hour if you like.

The work happens at home: the machine takes in the world and, by default, **drops it**.
What survives is checked against your own writing — the criteria you wrote while calm —
not against a model's opinion. Nothing is explained on the daily screen. No counts, no
reasons, no badges. Reasons belong to the weekly audit.

- let the AI drop things; do not let it answer for you
- let the human notice; do not let it decide
- keep what was dropped; show it once a week

The phone is a door, not a world.

## Honest state

This build is still a chat: you can ask it anything and it answers. The judgment layer
(`criteria`, dropping, the weekly audit) is what the design is about and is **not in this
repository yet**. What is here works: one screen, voice in, replies back, one narrow door.

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
