# Demo seed

Fills a **fresh** local stack with 200 users, ~600 real short videos (TikTok clips downloaded from a
hand-picked URL list, every one with its own audio) and four months of likes, comments, follows,
blocks, mutes, chat, notifications, stories, reports and admin actions, through the real APIs, then
backdates every store. Design: `docs/superpowers/specs/2026-10-27-demo-seed-design.md`.

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
2. Media (`SEED_MEDIA`, default `~/Projects/EProject/EProject4/seed-media`): one URL list per
   topic in `urls/<topic>.txt` (20 topics = the `TOPICS` of `fetch-pixabay.py`), downloaded with
   yt-dlp into `<topic>/<id>.mp4` + `<id>.info.json` (see below). Avatars and stories are cut
   from those clips by `make-media.py`: no other images are needed.
3. Captions: `scripts/seed/captions.json` holds title, hashtags, 8–10 comments and 4–6 creator
   replies **per clip** (`description` stays empty: TikTok has a single caption). `import-ytdlp.py`
   seeds each entry with the original hashtags plus the clip's topic and Explore tab tag
   (`content.py EXPLORE_TAGS`: the web client's Explore tabs each search one hashtag, so every
   clip of a topic carries its tab's tag); the Vietnamese title, comments and replies were
   then written by looking at two frames of the clip so the words match the footage
   (`captions/WRITER.md` in the session scratchpad was the brief). A clip in the manifest without
   an entry fails `test_content.py` and `scenario.py --check`. Add clips → add entries.
4. `make infra-reset && make infra-up` (wipes every volume), then start **all** Java services
   with `make run-*` (gateway, auth, user, video, media, interaction, story, recommendation,
   chat, notification, search, admin, analytics) after editing `.env`. `make run-media` must be
   up before the seed: it sets the public-read policy on avatars.

## Media from a URL list

Do it before `make seed` on fresh infra: more clips reshuffle the whole plan, so they don't mix
into an existing seed.

```
brew install yt-dlp ffmpeg
M=~/Projects/EProject/EProject4/seed-media
for t in food travel ...; do
  yt-dlp -a "$M/urls/$t.txt" -o "$M/$t/%(id)s.%(ext)s" --remux-video mp4 --write-info-json \
    --match-filter "duration <= 180" --download-archive "$M/yt-dlp-done.txt" \
    --sleep-interval 2 --max-sleep-interval 5 --ignore-errors
done
python3 -I scripts/seed/import-ytdlp.py "$M"             # manifest.json + captions.json skeleton, re-runnable
python3 -I scripts/seed/make-media.py "$M"               # 220 avatars + 48 stories (16 video) cut from the clips
python3 scripts/seed/test_content.py "$M/manifest.json"  # from the repo root
python3 -I scripts/seed/scenario.py --check "$M/manifest.json"
```

The folder is the topic: use one of `TOPICS` in `fetch-pixabay.py` so the clip goes to a persona
who posts about it. From the original caption, hashtags become the tags (accents stripped) and the
first line a placeholder title; write the real title/comments/replies into `captions.json` next
(re-runs keep edited entries). `test_content.py` flags a caption that hits the blocklist
(video-service would answer 422): edit that entry or delete the clip. `<id>.info.json` keeps the
uploader and source URL; keep these clips on the local demo only.

## Run

```
make seed SEED_AUTH_LOG=/path/to/auth-service.log   # ~2.5 h, resumable
make seed-backdate                                  # ~5 min, idempotent
```

`make seed` runs users (profiles, follows, blocks, mutes) → videos (waits for transcode +
moderation, ~1 h for 600 clips) → restores every held video beyond twelve so the review queue stays
small → engagement → chat → admin (reports, bans, warnings, a session revocation, comment removals,
managed moderation terms) → stories (images and short video stories).
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
- Banned on purpose: `tan.phat.deal`, `sale.soc.moingay` (permanent); `chu.bonsai` (5-day ban issued
  2 days ago), `nga.noithat` (7-day ban issued 3 days ago).

## Files

| File | Role |
|---|---|
| `fetch-pixabay.py` | (legacy) downloads Pixabay clips per topic; still the source of `TOPICS` |
| `make-media.py` | cuts avatars (square frames) and stories (portrait frames + short clips) from the manifest's videos |
| `content.py` | personas, topic-neutral comment / reply filler, chat banks, report reasons (`test_content.py` checks them and `captions.json` against the blocklist; pass the manifest path to also check coverage) |
| `import-ytdlp.py` | adds yt-dlp downloads to the manifest with their original caption; re-runs keep edited captions and (re)attach topic + Explore tab tags (`--check` self-test) |
| `retag.py` | pushes `captions.json` hashtags into the running Mongo, Elasticsearch and Redis cache for already-seeded clips (after editing tags, no re-seed) |
| `captions.json` | per-clip title, hashtags, comments, replies (keyed by clip id; `description` empty) |
| `scenario.py` | deterministic planner (`--check` self-test) |
| `seed.py` | create pass over HTTP, writes the ledger; `--refresh`; `--selftest` |
| `backdate.py` | second pass per store (Postgres ×3, Mongo ×3, Cassandra, Elasticsearch, Redis, ClickHouse) |
