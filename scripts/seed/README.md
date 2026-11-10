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
4. Flagged media for the open moderation queue (`realism.py`): the clips in `~/Downloads/tieucuc`
   (`SEED_FLAGGED`) and one explicit clip at `~/Downloads/test.mp4` (`SEED_EXPLICIT`). Missing files are
   skipped with a log line; nothing else needs them.
5. `make infra-reset && make infra-up` (wipes every volume), then start **all** Java services
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

For charts that show a growing platform, re-time the backdate (about 2 min, re-runnable; without
`--grow` it puts the planned times back):

```
python3 -I scripts/seed/backdate.py "$SEED_MEDIA" --grow 8 --months 12 --check   # print the monthly counts only
python3 -I scripts/seed/backdate.py "$SEED_MEDIA" --grow 8 --months 12
```

Signups, uploads and daily actives then rise steadily over 12 months ending at the moment it ran,
the last month about 8× the first. Run it again shortly before a demo, so the data reaches that day.

`make seed` runs users (profiles, follows, blocks, mutes) → videos (waits for transcode +
moderation, ~1 h for 600 clips) → restores every clip the pipeline held (false positives: the review
queue belongs to the flagged clips; each restore is deleted from the audit log right after, so the log
shows only moderators' decisions) → engagement → chat → admin → stories → realism.

What the console shows comes from real calls, and every decision points at something that earns it:

- **History** (`scenario.py`, decided): the two spam accounts post product listings (`SPAM_VIDEO_CAPTIONS`)
  and shop comments; each listing is reported and taken down, the comments removed, then both are banned.
  Harassing and threatening comments (`REMOVED_COMMENTS`) are reported (half by the creator they target),
  removed, and their authors warned; the two temp-banned personas write two each before their ban.
  Criticism (`DISMISSED_COMMENTS`) and ordinary videos reported anyway are dismissed. Every action carries
  a moderator note (`ADMIN_NOTES`). Managed terms (`ADMIN_TERMS`) are added one by one over the months;
  two (`REMOVED_TERMS`) are taken out again.
- **Open queue** (`realism.py`, ~a week old, nothing decided): the flagged clips are uploaded and the real
  pipeline holds most of them for review (gore, violence, accident, gambling) and rejects `test.mp4`
  (nsfw); the ones it publishes are then reported. Reported comments from self-harm (10) and threats (9)
  down to spam (2), message-request scams and explicit bait reported from the conversation, an account
  impersonating the demo creator, a scam account. Plus five sign-ups that never verified, two
  deactivated accounts, one scheduled for deletion, and three videos their owners deleted (trash).
- **Sign-ins**: every persona signs in from one phone (some also a laptop) in a Vietnamese city, sent as
  `User-Agent` + `X-Device-Id` + `X-Client-Ip` with the internal token, so auth-service records the device
  and, with the GeoIP file, the place. Run auth-service with `make run-auth` (the GeoIP path is relative
  to the module directory) or set `GEOIP_DATABASE`.
The admin step logs in as `ADMIN_EMAIL`; with `SEED_AUTH_LOG` pointing at the auth-service log the
OTP is read automatically, otherwise the script asks for it on stdin (read it from the log line
`admin login OTP for ... = 123456`). The device is remembered, so re-runs skip the OTP.

Everything created is recorded in `SEED_MEDIA/ledger.json`; a crashed run continues where it
stopped. Phases can be run alone: `python3 -I scripts/seed/seed.py <media> --phase engagement`.

## TikTok Studio for a real account

`studio.py` gives one account (default `vominhhung031`, signed in with Google, so no password: tokens
are minted with `JWT_SECRET` from `.env`) a channel for Studio, after the main seed:

    SEED_AUTH_LOG=<auth-service log> python3 scripts/seed/studio.py [--owner name] [--media ~/Projects/EProject/EProject4/studio-media]

It re-encodes `studio-media/*.mp4` to H.264 (HEVC hangs media-worker; clips that do not decode are left
out), uploads them with Vietnamese captions written per clip (`CLIPS`), restores any the classifier
holds (and drops those restore rows from the audit log), gives the personas birth dates, then plays the
audience through the API: views and watch sessions with traffic source and search phrase, likes,
comments in the clip's own topic with the owner's replies and hearts, shares, saves, reposts, follows
credited to a video, a few unfollows. Anonymous plays (user_id 0) are added straight to ClickHouse and
the view counters, since 200 personas cap the viewers of a clip. Last, everything is re-timed: posts on
the clips' original upload dates shifted to end yesterday, sessions front-loaded at Vietnamese viewing
hours, later clips doing better. State: `<media>/studio-ledger.json`; `--only <phase>` re-runs one
(`prep,upload,plan,birthdates,engage,settings,backdate`).

The owner answers ~200 comments, over the 100/hour limit: start interaction-service with
`COMMENT_COOLDOWN=0s COMMENT_PER_MINUTE=0 COMMENT_PER_HOUR=0 COMMENT_PER_DAY=0 COMMENT_PER_VIDEO_PER_HOUR=0
COMMENT_DUPLICATE_MAX_COPIES=0` and run with `STUDIO_NO_PACE=1`, then restart it normally. Otherwise
the script paces itself (about two hours). Do not run `backdate.py` after it: its watch-row re-draw
reassigns the viewers of these clips at random.

## Demo window

The seeded world ages. Within **5 days** of seeding: the temporary ban (`chu.bonsai`) expires, and
`StaleReportSweep` starts dismissing pending reports with expiring reasons 30 days after their
backdated `created_at` (realism keeps them under 6 days old). `backdate.py --grow` re-anchors the open
queue to the day it runs, so re-run it shortly before a demo.
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

- Users: every persona in `content.py` and every account in `realism.NEW_ACCOUNTS`, password `Demo@12345`,
  email `<username>@example.com`.
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
| `realism.py` | the open moderation queue and recent account activity (`--check` self-test); its times are ages, written by `backdate.py` |
| `backdate.py` | second pass per store (Postgres ×3, Mongo ×3, Cassandra, Elasticsearch, Redis, ClickHouse) |

## Moving the seed to production

The seed itself is local-only (it dials service ports, reads OTPs from the auth log, needs the
always-pass Turnstile key). To show it on the real server, export the local stores and load them there:

```
bash scripts/seed/export-prod.sh ~/seed-export            # ~6 GB, mostly the bucket
scp -r ~/seed-export scripts/seed/import-prod.sh homebox:~/   # over Tailscale, not through Cloudflare
ssh homebox 'bash ~/import-prod.sh ~/seed-export https://media.nowaa.site'
```

`import-prod.sh` stops the Java services, replaces every
store, rewrites `http://localhost:9000` to `MEDIA_PUBLIC_URL` in videos, avatars and the search index,
clears the laptop's sessions/codes/devices from `auth_db`, and starts the services again. Prod data is
gone afterwards; run it only on a fresh deployment. The laptop's ADMIN account comes along with its
laptop password — change it after the first sign-in.
