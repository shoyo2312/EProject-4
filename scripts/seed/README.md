# Demo seed

Fills a **fresh** local stack with 50 users, 200 real videos and four months of likes, comments,
follows, chat, notifications, reports and admin actions, through the real APIs, then backdates
every store. Design: `docs/superpowers/specs/2026-10-27-demo-seed-design.md`.

Fresh infra only. The seed is additive and collides on unique indexes (usernames, upload keys,
follows) if run twice on the same data.

## Before you start (once)

1. `.env` must contain:
   ```
   TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA
   OTP_LOG_TO_CONSOLE=true
   MAIL_PASSWORD=
   ADMIN_EMAIL=...        # already set
   ADMIN_PASSWORD=...     # already set
   ```
   The first line is Cloudflare's always-pass test secret (registration still calls Cloudflare,
   so the machine needs internet). The second lets the seed read the admin login OTP from the
   auth-service log. The third stops 50 OTP mails to `@example.com` going out through a real key.
2. Media: `make seed-media` (needs `~/.pixabay_key`; ~1.8 GB into `SEED_MEDIA`, default
   `~/Projects/EProject/EProject4/seed-media`). Already done on this machine.
3. `make infra-reset && make infra-up` (wipes every volume), then start **all** Java services
   with `make run-*` (gateway, auth, user, video, media, interaction, story, recommendation,
   chat, notification, search, admin, analytics) after editing `.env`. `make run-media` must be
   up before the seed: it sets the public-read policy on avatars.

## Run

```
make seed SEED_AUTH_LOG=/path/to/auth-service.log   # ~1.5 h, resumable
make seed-backdate                                  # ~5 min, idempotent
```

`make seed` runs users → videos (waits for transcode + moderation, 30–60 min) → restores every
held video beyond six so the review queue stays small → engagement → chat → admin → stories.
The admin step logs in as `ADMIN_EMAIL`; with `SEED_AUTH_LOG` pointing at the auth-service log the
OTP is read automatically, otherwise the script asks for it on stdin (read it from the log line
`admin login OTP for ... = 123456`). The device is remembered, so re-runs skip the OTP.

Everything created is recorded in `SEED_MEDIA/ledger.json`; a crashed run continues where it
stopped. Phases can be run alone: `python3 -I scripts/seed/seed.py <media> --phase engagement`.

## Demo window

The seeded world ages. Within **5 days** of seeding: the temporary ban (`chu.bonsai`) expires, and
`StaleReportSweep` starts dismissing pending reports with expiring reasons 30 days after their
backdated `created_at` (the seed keeps them under 14 days old, so about 16 days of margin).
Re-seed (`make infra-reset` …) if the demo slips further than that.

## Before the demo

`reco:trending` only reflects the last 24 h of engagement, so a day after seeding the For You
feed of a user without tag affinity is empty. Within a few hours of the demo run:

```
make seed-refresh
```

It sends ~300 fresh likes, comments and views on the 30 most-viewed videos.

## After the demo

- Delete `SEED_MEDIA/ledger.json`: it holds every demo user's access token and the admin's
  remember-device token in plain text.
- Remove `OTP_LOG_TO_CONSOLE=true` and the always-pass `TURNSTILE_SECRET_KEY` from `.env`
  (CLAUDE.md §Dev-only affordances).

## Accounts

- Users: every persona in `content.py`, password `Demo@12345`, email `<username>@example.com`.
  The demo account is the first creator, `minhanh.vlog`.
- Admin: `ADMIN_EMAIL` / `ADMIN_PASSWORD` from `.env` (OTP from the auth-service log).
- Banned on purpose: `tan.phat.deal` (permanent), `chu.bonsai` (5-day ban issued 2 days ago).

## Files

| File | Role |
|---|---|
| `fetch-pixabay.py` | downloads clips/avatars/story images per topic, writes `manifest.json` |
| `content.py` | personas, caption templates, comment / reply / chat banks, report reasons (`test_content.py` checks them against the blocklist) |
| `scenario.py` | deterministic planner (`--check` self-test) |
| `seed.py` | create pass over HTTP, writes the ledger; `--refresh`; `--selftest` |
| `backdate.py` | second pass per store (Postgres ×3, Mongo ×3, Cassandra, Elasticsearch, Redis, ClickHouse) |
