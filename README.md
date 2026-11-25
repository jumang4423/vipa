# vipa

A flip phone that carries nothing — no feed, no shop, no count — and an agent at home that
does the reading for you.

The phone is a KYF39 because it can do very little and its battery lasts about ten days.
Nothing on it can ask to be looked at.

The bound is not on what the phone can do. It is on what reaches it, and on how slowly it
can be read. One door, one 480×854 screen, four keys. Nothing here is a moral test.

## Requirements

- **Hermes** on a machine that stays on
- a **KYF39** flip phone
- a **Groq API key** — this is how you speak to it. The phone has no usable keyboard, so its
  only input is voice, and voice is transcribed by Groq. No key, no input.
- **ngrok** — only if you want to reach it from outside the house

## Install

Tell Hermes:

```
https://github.com/jumang4423/vipa — read README.md and INSTALL.md, then run install.sh to the end.
```

That is all. The agent installs the plugin, the platform config, the phone app and, if you
ask for it, the ngrok tunnel. It answers with one `NEXT` line and, if it needs you, `NEED` lines.

## Status

Still a chat. The judgment layer — criteria, dropping, the weekly audit — is not here yet.
See [INSTALL.md](INSTALL.md) for the details.
