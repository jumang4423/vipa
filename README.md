# vipa

**vipa** is *vipassanā*, cut short. A flip phone that carries nothing — no feed, no shop, no
count — and an agent at home that blocks the dynamics collective fictions generate and
passes on only the minimum a life actually owes to society.

## Why

Vipassanā is not a technique for feeling better. It is the practice of seeing what is
actually there — the sensation before the story about it — so the story stops being mistaken
for the world. Almost everything that reaches a phone is story: what a crowd finds
interesting, what a screen wants you to compare, what a number says about your standing.
None of it is sensation. All of it is a demand.

This builds the opposite. The phone is a KYF39: it can do very little, its battery lasts
about ten days, and nothing on it can ask to be looked at. The machine at home blocks rank,
comparison, consensus pressure, the race for standing — without arguing with them — and lets
through only what a life owes: a deadline, a person waiting for an answer, a commitment, a
change that alters today's plan.

It is for real sensation, plain ethics, peace and stability. Not productivity, not
minimalism, not resistance — resistance stays in the same arena, and the point is to leave
it.

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

## How it decides

Nothing is scheduled by vipa itself. You register jobs in Hermes as you like — and
`hermes-plugin/vipa/criteria.md`, the vow, decides whether a job may be delivered to the
phone at all.

Allowed: a deadline, a person waiting for an answer, a commitment, a change that alters
today's plan.

Refused: follower counts, likes, scores, streaks, rankings — anything that exists to make
you check again. "Yesterday: +39 followers" is refused, even if you asked for it. A job that
tells you someone you owe an answer to has written is allowed.

Both `philosophy.md` and `criteria.md` are injected into the system prompt of every turn on
this platform, so the agent carries them without being reminded.

## Status

Still a chat. Criteria are enforced in the agent's judgment, not in code; dropping logs and
the weekly audit are not implemented yet. See [INSTALL.md](INSTALL.md) for the details.
