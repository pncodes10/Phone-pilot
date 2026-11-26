# Cloudflare relay (fixed address, no tunnel)

A small Cloudflare Worker that passes **already-encrypted** messages between `phonectl.py` and the phone. It replaces
the Termux + `cloudflared` quick tunnel: the address never changes, so the pairing code is given once.

```
phonectl.py ──POST /ctl/<id>──▶  Worker (Durable Object)  ◀──GET /poll/<id> (long poll), POST /reply/<id>── phone
```

- The room name `<id>` is a hash of the pairing key; every message is AES-256-GCM encrypted with the key, so the
  Worker holds no secrets, needs no configuration, and cannot read or forge commands.
- The phone polls over plain HTTPS (about every 20 s when idle, instantly when a command arrives), so it survives
  Wi-Fi/mobile changes. No WebSocket library is needed.
- Free plan: it uses a SQLite-backed Durable Object (the only kind the free plan allows). It is busy only while the
  phone's relay mode is switched on, and a test session of a few hundred commands is far below the daily limits
  (100,000 requests/day, 13,000 GB-s/day). Check Cloudflare's current limits before relying on this.

## Deploy once (free Cloudflare account)

```bash
cd relay-worker
npx wrangler login        # opens the browser; sign in or create the free account
npx wrangler deploy       # prints https://phonepilot-relay.<your-subdomain>.workers.dev
```

## Use

1. In the Phone Pilot app (0.11+), type your `https://phonepilot-relay.<subdomain>.workers.dev` address into
   **Relay server**, tap **Start relay mode**, then **Copy pairing code**. It looks like
   `phonepilot:cf:https://….workers.dev#<64 hex characters>`. The code stays the same until you tap
   "New pairing code".
2. Give that code to Claude (or `export PHONE_PAIRING=...`) and run `python3 phonectl.py get_screen` as usual.

Errors: `503 phone not connected` = relay mode is off or the phone is offline; `504` = the phone did not answer in time.
