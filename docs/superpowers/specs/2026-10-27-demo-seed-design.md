# Demo seed — four months of real-looking data — design

Date: 2026-10-27 · Status: draft
Repos: `tiktok-backend` only (scripts under `scripts/seed/`). The web client (`tiktok-cloned`) and
console (`tiktok-admin`) only read what is seeded; nothing changes there.

## 1. Why

The project is demoed to a teacher. A fresh database shows one admin, no videos, an empty feed and
an empty console. The demo has to open on a platform that looks like it has been alive for four
months: a feed with many creators and topics, comments under videos, people following each
other, stories on the ring, a chat inbox with history, notifications, and an admin console with a
report queue, a review queue, an audit trail and charts that go back four months.

Nothing here is a product feature. It is a one-off operator tool run on a developer's machine.

## 2. Decisions

Decided by the user:

- **Audience and size.** Demo for a teacher. 50 users, about 200 videos across many topics,
  history spread over the last 4 months (2026-06-27 → 2026-10-27). Likes, comments, follows,
  stories, chat, notifications, shares all present.
- **Both sides.** The admin console is part of the demo: held videos, user reports, a banned
  account, held comments.
- **Media comes from Pixabay**, downloaded by `scripts/seed/fetch-pixabay.py` into
  `~/Projects/EProject/EProject4/seed-media/<topic>/<id>.mp4` plus `avatars/`, `stories/` and a
  `manifest.json` carrying Pixabay's tags per file. Portrait and landscape both accepted (the web
  client handles landscape). One folder per topic; captions are written by the seed, in
  Vietnamese, from the topic and the Pixabay tags.
- **Full stack runs locally** on the demo machine (Mac mini M4 Pro, 24 GB), including
  `moderation-service`. All 13 infra containers and all 13 Java services are already up.

Decided by me (my calls, say so if wrong):

- **Everything goes through the real HTTP APIs**, as the real users, in chronological order. Then a
  second pass rewrites timestamps directly in each store. Two reasons: (a) the real pipeline
  produces real transcodes, thumbnails, previews, moderation scores, search index entries and
  notifications, which hand-written documents would not; (b) Snowflake ids encode creation time
  and several lists (comments, replies, shares) sort by id, so creating things in the order they
  will appear to have happened makes a plain timestamp UPDATE enough. No re-keying.
- **The seed talks to service ports directly** (`:8081`…`:8096`), not the gateway. Every service
  validates the JWT itself, and the gateway's 20 req/s per-IP limit would make the run take hours.
- **Views and watch history are written directly**, not through `POST /view`. Every view through the
  API is one HTTP call capped at 60 per (user, video) per hour; a hot video needs tens of
  thousands. View counts go straight into Cassandra `video_counters`, Mongo `viewCount`, and ES
  `viewCount`. Watch rows go straight into ClickHouse `watch_events` (its sorting key contains
  `occurred_at`, so they cannot be backdated after the fact anyway).
- **Stories are not backdated.** `expiresAt` is a Mongo TTL index and `stories/` media has a 3-day
  MinIO lifecycle rule; a backdated story is deleted by the database. Stories are seeded fresh,
  all within the last 24 hours, so the ring is full on demo day.
- **Python 3 standard library only** for the seed itself; direct store writes go through the CLIs
  already inside the containers (`psql`, `mongosh`, `cqlsh`, `clickhouse-client`, `redis-cli`)
  via `docker exec`, and Elasticsearch over HTTP. No new dependencies, nothing to install.
- **One shared password** for all 50 demo users: `Demo@12345`. Emails are
  `<username>@example.com`. The demo signs in as one of the creators; the password is in the
  README of `scripts/seed/`.
- **Admin actions use the existing admin account** (`ADMIN_EMAIL` / `ADMIN_PASSWORD` from `.env`).
  The seed logs in as admin once; admin login needs the e-mail OTP, so the seed prompts for it on
  stdin and the operator reads it from the auth-service terminal (`OTP_LOG_TO_CONSOLE=true`).

## 3. Preconditions (operator does these once)

1. `make infra-reset && make infra-up` — the seed assumes empty stores. It is not safe to run twice
   on the same data (unique indexes would refuse the second run half-way; see §9).
2. In `.env`:
   - `TURNSTILE_SECRET_KEY=1x0000000000000000000000000000000AA` (Cloudflare's always-pass test
     secret; the seed sends the matching dummy token `XXXX.DUMMY.TOKEN.XXXX`). Registration and
     admin login call Cloudflare with this secret, so the machine needs internet. Verified today:
     with the current `.env`, `register/start` with the dummy token returns
     `TURNSTILE_VERIFICATION_FAILED`, so this line is missing.
   - `OTP_LOG_TO_CONSOLE=true` (admin login OTP; dev-only flag, see CLAUDE.md §Dev-only).
   - `ADMIN_EMAIL`, `ADMIN_PASSWORD` set (already are: user `admin` exists, verified).
   - `MAIL_PASSWORD` empty for the run: `/register` mails an OTP to every `<username>@example.com`
     (async, failures are swallowed), and with a real Resend key 50 bounces hurt that account.
     The admin OTP is read from the log, not from mail.
3. All Java services started via `make run-*` **after** editing `.env` (the Makefile exports it),
   plus `make moderation-up`. `make run-media` must run once before any avatar is uploaded: it is
   what sets the public-read policy on `avatars/*`.
4. Media downloaded: `python3 scripts/seed/fetch-pixabay.py ~/Projects/EProject/EProject4/seed-media`
   (already done: 200 videos in 20 topics, 60 avatars, 40 story images, 1.8 GB).

## 4. The scenario

### 4.1 Timeline

`T0 = 2026-06-27 00:00 +07`, `T1 = now`. Every object gets a **planned instant** between T0 and
T1 before anything is sent. The whole scenario is generated up front as one event list sorted by
planned instant, from a fixed random seed (`--seed 2026`), so a re-run on a fresh database gives
the same world.

- **Users** join between T0 and T0+10 weeks, front-loaded (most in the first month, a trickle
  later). Two users join in the last fortnight so the admin user list shows recent sign-ups.
- **Videos** are spread over all 17 weeks, 8–16 per week, never more than 4 on one day, posting
  hours weighted to 11:00–14:00 and 19:00–23:00 local time.
- **Engagement on a video** follows a burst-then-tail curve: ~60 % of its likes/comments/views in
  the first 3 days, the rest spread to T1. Nothing on a video predates its publish instant.
- **Follows** happen mostly in a user's first two weeks and after they like a creator's video.
- **Chat** threads start after a follow, with messages spread over weeks; 5 threads get a message
  in the last 48 h so the inbox has unread badges.
- **Reports, bans, takedowns** are spread over the 4 months (see 4.5); the pending ones are
  recent (< 30 days) or use non-expiring reasons, so `StaleReportSweep` (grace 30 d) does not
  dismiss them before the demo.
- **Stories**: 12 stories from 8 users, all planned within the last 20 hours.

### 4.2 People (50)

- 50 users with Vietnamese-flavoured usernames (`minhanh.vlog`, `bepcuatrang`, `hieu.travel`…),
  display names with diacritics, a bio per persona, an avatar from `avatars/` (60 downloaded; the
  10 spare are for users whose upload fails).
- Roles in the graph: 8 **creators** (post 10–20 videos each, 1–3 k followers in-demo), 17
  **regulars** (3–8 videos each), 25 **viewers** (0–2 videos, lots of likes/comments). Creators
  are followed by 60–90 % of users; viewers by 0–5.
- 2 users have `privateAccount = true`; one has a pending follow request from someone.
- 1 creator has the comment keyword filter on (`comment_keywords = ["quảng cáo","inbox"]`) so a
  few comments land in their held queue.
- Follow graph: ~600 follows, ~40 % mutual among the regular/creator set (so `FRIENDS` visibility
  and DM `FRIENDS` audience have meaning).

### 4.3 Videos (200)

- Each file from the manifest is assigned to an owner whose persona fits the topic (the food
  creator posts food, but a viewer may post a pet clip).
- Caption = title (≤150) + description with 2–4 hashtags, Vietnamese, generated from a per-topic
  template bank × the Pixabay tags, never two identical. Tags array = 3–5 topic hashtags.
- Visibility: ~90 % PUBLIC, 6 % FRIENDS, 4 % PRIVATE.
- Counters are skewed (Zipf): the top 10 videos get 20–60 k views and 1–4 k likes; the median
  video 300–2 000 views and 20–150 likes; a long tail under 100 views. Comment count ≈ 4 % of likes,
  shares ≈ 2 %, reposts ≈ 1 %, saves ≈ 3 %.
- Three captions deliberately carry a phone number / a link so the **real** text screener holds
  them (`PENDING_REVIEW`), and whatever the frame/harm classifiers hold on their own (boxing, gym,
  fire are likely) stays held too, **up to 6** videos total in the review queue. Any further held
  video is approved by the seed through the admin restore endpoint so the feed is not short.
- 3 published videos are later **taken down** by the admin (tied to reports in 4.5).

### 4.4 Engagement (through interaction-service, :8085)

Approximate totals: 6 000 likes, 1 200 comments + 350 replies, 400 shares, 150 reposts, 300 saves,
80 comment-likes. All sent as the acting user, in planned order, with the seed respecting the
service's own limits (3 s comment cooldown per user, 10/min, 100/h, 500/day, duplicate-text guard:
comment texts come from a bank of ~400 Vietnamese lines with light variation and are unique per
video). A comment that is refused (422, rules) is dropped from the plan. Each refusal is also a
**strike** in Redis (3 in 24 h → `COMMENTING_RESTRICTED`, real time, not backdatable), so the
seed treats `COMMENTING_RESTRICTED` as "drop every later comment of that persona in this run"
and the comment bank is screened against `blocklist-vi.txt` up front so refusals stay rare.

Views: planned per video from the counters above, written directly (§5). Watch rows for
ClickHouse: one per planned view, `watched_ms` 30–100 % of duration.

### 4.5 Admin side (admin-service :8096, video-service :8083)

- 30 reports from ordinary users over the period (targets: 12 videos, 8 users, 10 comments;
  reasons from the seeded `report_reason_weights` labels). Outcomes: 14 resolved (3 takedowns,
  2 bans, 4 comment removals, 5 warnings), 8 dismissed, 8 still pending (the queue the teacher
  sees).
- Bans: one permanent (a spam persona, 30 days ago), one 7-day ban issued 2 days ago (still
  active on demo day, so their videos/comments are hidden and the console shows the expiry).
  `banned_until` is real time, so the seed issues it with `banDays=5` and backdates the action
  by 2 days: the console then reads "7-day ban, 2 days ago, expires in 5 days".
- 10 comments removed by the admin (some via report resolution, some directly), so the "removed"
  comment scan and the audit log have entries. Removals strike the author in real time, so they
  are concentrated on the two banned personas (6) and spread at most 1 per other author (4);
  the account the demo signs in as (the first creator) is never struck.
- 6 held videos in the review queue (4.3), 2 approved by admin earlier (audit log shows
  `RESTORE_VIDEO`).
- Admin session: one login (OTP from stdin), `rememberDevice` so re-runs skip the OTP.

### 4.6 Chat and notifications

- 15 conversations (mutual followers, so `dmAudience = FRIENDS` passes), 5–20 messages each from a
  bank of casual Vietnamese exchanges; 5 threads active in the last 48 h.
- Notifications are produced by the consumers (like, comment, follow, story, welcome, admin
  ban/warn/takedown). The seed only backdates them (§5). ~40 % are left unread for the users the
  demo signs in as.

## 5. Backdating (second pass)

The seed keeps a **ledger** (`seed-media/ledger.json`): for every object it created, the ids the
API returned and the planned instant (ISO-8601 UTC, millisecond precision). The backdate pass is
idempotent (re-running sets the same values) and runs only after the create pass has finished and
every video has left `PROCESSING`/`PENDING_MODERATION` (the `StuckVideoSweeper` fails a backdated
video that is still in those states).

| Store | What | How |
|---|---|---|
| auth `auth_db.users`, `auth_security_events` | `created_at`, `updated_at`, `email_verified_at`, `last_login_at`; the seed's own login rows in `auth_security_events` → the user's join instant (the console shows them per user) | `psql` UPDATE by id / user_id |
| user `user_db.user_profiles`, `user_follows`, `user_follow_requests`, `user_blocks` | `created_at`, `updated_at` | `psql` UPDATE by id / (follower, following) |
| video `video_db.videos` | `createdAt`, `publishedAt`, `updatedAt`, `moderation.checkedAt`, `textModeration.checkedAt`; counters `viewCount` | `mongosh` `$set` by `_id`; then `DEL video:{id}` in Redis |
| ES `videos` | `createdAt` (`yyyy-MM-dd'T'HH:mm:ss.SSS` UTC), `viewCount` | `_bulk` partial updates |
| Redis reco | `ZADD reco:video:published` and `reco:tag:{tag}` with the planned epoch seconds; `reco:video:watches` / `reco:video:completions` with the planned view and completion counts (the ranker reads them, and views bypass the API) | `redis-cli` |
| Cassandra `interaction_ks` | `comments_by_video.created_at`, `shares_by_video.created_at`, `comment_likes.created_at` (UPDATE); `likes_by_user`, `reposts_by_user`, `saves_by_user_time` (DELETE + INSERT, `created_at` is a clustering column) **and** the paired `likes_by_video` / `reposts_by_video` / `saves_by_user` `created_at` set to the identical millisecond; `video_counters.view_count` `+= planned views` | `cqlsh` batch file; done while interaction-service is idle (the service's own writes are LWT) |
| chat `chat_db` | `messages.sentAt`; `conversations.createdAt`, `lastMessageAt` (= newest `sentAt`), `lastReadAt.<uid>` shifted alongside (readers of threads marked unread keep an older `lastReadAt`) | `mongosh` |
| notification `notification_db.notifications` | `createdAt` | `mongosh`: matched by `(recipientId, actorId, type, referenceId)` to the ledger action that caused it; welcome (`SYSTEM`, actor null) → user's join instant; moderation `SYSTEM` → the action's instant |
| admin `admin_db.reports`, `moderation_actions` | `created_at`, `updated_at`, `resolved_at` | `psql` UPDATE by id |
| ClickHouse `analytics_db` | `engagement_events.occurred_at` (PUBLISHED/LIKED/COMMENTED/SHARED rows, matched by `(event_type, video_id, user_id)`), `video_tags.published_at`, `user_signup_events.occurred_at` | `ALTER TABLE … UPDATE … WHERE` mutations; `watch_events` inserted directly with backdated `occurred_at` |

Not touched: Snowflake ids (they keep real time; no runtime code derives time from them),
`processed_events`/`inbox_events` (TTL/infra), `banned_until` (real expiry), stories.

## 6. Trending and the feed on demo day

`reco:trending` is rebuilt every minute from the last 24 hourly buckets with 0.85/h decay, and every
engagement the seed sends lands in the bucket of the *current* hour. So right after the seed,
trending = the most-engaged videos overall (good), and it decays to empty about a day later; a
user with no tag affinity then gets an empty `/feed`. Mitigation: `seed.py --refresh` sends
~300 fresh likes/views/comments from random users on the top videos (through the API, timestamps
left as now). Run it within a few hours of the demo. The ledger makes it idempotent-ish (new
likes only from users who have not liked that video).

## 7. Deliverables

```
scripts/seed/
  README.md          how to run, order, the password, what to do before the demo
  fetch-pixabay.py   (exists) download media + manifest
  content.py         personas, caption templates, comment/chat banks (Vietnamese)
  scenario.py        builds the deterministic event list from manifest + content (pure, testable)
  seed.py            create pass over HTTP (+ --refresh); writes ledger.json
  backdate.py        second pass per store, reads ledger.json
Makefile targets: seed-media, seed, seed-backdate, seed-refresh
```

`scenario.py` has one self-check (`python3 scenario.py --check`): no engagement before its video's
publish instant, no object before its user's join instant, counters within the Zipf bounds,
captions unique. That is the only test; the rest is exercised by running it.

## 8. Constraints and traps the builder must respect

- Create pass order is the planned order. Videos are uploaded in their planned order first (the
  pipeline is sequential in media-worker, ~5–20 s per clip → 30–60 min for 200); the seed waits
  until every video is out of `PROCESSING`/`PENDING_MODERATION` (poll `GET /videos/users/{uid}` on
  :8083 as the owner) before sending any engagement. Then engagement in planned order.
- Video upload = `POST /videos/upload-url` → multipart POST to MinIO with `formFields` then `file`
  → `POST /videos` with the returned `fileUrl`.
- Avatar = `POST /users/me/avatar` multipart (JPEG/PNG/WebP ≤ 5 MB). `avatarUrl` must stay on the
  MinIO host.
- Users: `POST /auth/register` (Turnstile dummy token) then `UPDATE users SET email_verified=true,
  email_verified_at=now()` via `psql`, then `POST /auth/login`. Profiles appear in user-service a
  few seconds later from the Kafka event; the seed waits for `GET /users/{id}` to return 200
  before touching the profile.
- Interaction writes 404 when the video is not PUBLISHED; the plan only engages PUBLISHED videos
  (held/private videos get no engagement, friends-only videos only from friends).
- `REPOST` notifications are dropped by the consumer when the event is older than 24 h; irrelevant
  here because events are sent now.
- Comment ids order the comment lists; **never** re-key comments. Likes/reposts/saves listing rows
  are re-inserted because their clustering key is the timestamp.
- The three `PENDING_REVIEW` captions must not use blocklist words (severity 3 = 422, nothing
  stored); a phone number pattern is enough to get severity 2.
- Mongo `video:{id}` cache (60 s) and `interaction:counters:{id}` (300 s) in Redis are deleted after
  counters are written, or the client shows stale numbers for up to 5 minutes.
- Do not run the seed against data that matters: it is additive and will collide on unique indexes
  (usernames, `rawFileUrl`, follows).

## 9. Non-goals

- No re-runnable "upsert" seed. Fresh infra only.
- No fake moderation scores: scores come from the real classifiers.
- No ClickHouse `watch_events` backfill beyond what the planned views imply; no screen-time data.
- No mobile-specific data. No social-login accounts. No new-device alerts (would need distinct
  `X-Device-Id` per login; skipped).
- No production safety: this is a developer-machine tool and reads dev credentials from compose
  defaults.

## 10. Open for the builder

- Exact persona list, caption templates and comment banks: write them; keep them clean (no
  blocklist terms, no real people).
- Concurrency in the create pass: sequential is fine for users/follows; engagement may use a small
  pool (4 threads) as long as planned order within a video is preserved.
- How the ledger maps a notification to its action when the same (recipient, actor, type, ref)
  tuple occurs twice (collapsed within 24 h by the service anyway): take the earliest planned
  instant.
