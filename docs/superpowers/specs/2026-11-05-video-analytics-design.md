# Per-video analytics, TikTok Studio parity — design

Date: 2026-11-05 · Status: approved 2026-11-05
Spans both repos: analytics-service, interaction-service, user-service and auth-service here; the
Studio page, the feed's watch reporting and the sign-up modal in `tiktok-cloned`.

## 1. Why

`/studio/analytics/{videoId}` today shows four lifetime tiles (views, play time, average watch,
watched-full), a retention line and a per-day engagement strip. That is the skeleton of TikTok
Studio's video page and nothing more: no traffic source, no search terms, no audience, no saves, no
follower attribution, no comparison with the previous window, and the charts cannot be hovered. The
owner wants this to work as a real product, not a demo — which means the missing numbers come from
data the platform actually collects from viewers, not from seed scripts.

Current state worth knowing (all verified in the code):

- Watch sessions are already recorded once per viewing (`useWatchSession` → `POST
  /interactions/videos/{id}/watch` → `VideoWatchEvent` on `interaction.watch-events` → ClickHouse
  `watch_events`). The event carries `videoId, userId, watchedMs, durationMs, completed` and
  nothing about where the viewer came from.
- Saves are published by interaction-service (`VideoSavedEvent` on `interaction.save-events`) but
  analytics-service does not consume them.
- Follows reach ClickHouse (`follow_events`) but carry no source video.
- The sign-up modal collects month/day/year and validates the age gate client-side
  (`schemas.ts` `checkBirthday`), then throws the value away: `CompleteSignupRequest` has no
  birth date, no service stores one.
- Analytics is ClickHouse (`ReplacingMergeTree`, reads under `FINAL`), days in `Asia/Ho_Chi_Minh`,
  a 60 s in-memory memo per question, and a privacy threshold `analytics.creator.min-audience`
  (default 100) already gating the account-level viewer breakdowns.

## 2. Decisions

Decided by the owner:

- **Product-grade, not demo-grade.** Every number on the page must be derivable from real viewer
  behaviour going forward. Historical rows that predate a new field show as "Other"/"Unknown"
  rather than being backfilled with guesses.
- **Birth date goes in the database.** Sign-up already asks for it; it is now saved. Nullable.
- **Social sign-in (Google/Facebook) does not fetch the birthday from the provider** — Google needs
  the `user.birthday.read` scope most people keep private, Facebook's `user_birthday` needs app
  review. Instead the app asks once after sign-in, the way TikTok does.
- **No gender. No country/location.** Not collected, not shown.
- **Scope of the page** (everything below is in; nothing else is):
  1. Overview: add **New followers** (followers gained from this video) as a fifth tile.
  2. Retention chart: hover readout and a one-line insight ("Most viewers stopped watching at
     0:07").
  3. Engagement strip: add **Saves**; every metric shows change vs the previous window; the chart
     can be hovered.
  4. **Traffic source** breakdown.
  5. **Search queries** that led to the video.
  6. **Viewers**: total, new vs returning, followers vs non-followers, age groups.

Proposed in the conversation and not objected to (flag if wrong):

- Viewer breakdowns (new/returning, follower split, age groups) are withheld under the same
  threshold the account-level page already uses, `analytics.creator.min-audience`. Below it the
  page says what TikTok says: you'll see this once you have enough viewers.
- Age groups are TikTok's: 13–17, 18–24, 25–34, 35–44, 45–54, 55+, plus **Unknown** for viewers
  without a birth date.

## 3. The page

One scrolling page, same shell and `RangeSelect` (7/28/60 days) as today. Lifetime totals stay
lifetime; everything described as "in the window" follows the range picker. Order top to bottom:

1. **Header** — unchanged (back arrow, `PostCell`, range select, `StatusNote`).
2. **Overview** — five tiles: Video views · Total play time · Average watch time · Watched full
   video · New followers. Lifetime. (New followers lifetime = all follows attributed to this video.)
3. **Retention rate** — the existing 21-point line. Hovering shows a vertical cursor, the time at
   that point and "NN% still watching". Under the chart one line of insight, computed on the
   client from the 21 points: the step with the largest drop, phrased as *Most viewers stopped
   watching at m:ss*. If the largest drop is the very first step (0 % → 5 %), say *Most viewers
   left in the first few seconds*. Hidden when views = 0 (the chart already shows "No views yet").
4. **Engagement in the last N days** — `MetricTabs` with five cells: Video views · Likes ·
   Comments · Shares · Saves. Each cell shows the window total and `changeLine(value, previous)`
   against the previous window of the same length (already supported by `MetricTabs.previous`, just
   never fed). Hovering the chart shows date + value for the picked metric.
5. **Traffic source** — a list of rows, highest first: label, horizontal bar, percentage of the
   window's views. Labels as TikTok shows them: *For You*, *Following*, *Friends*, *Explore*,
   *Personal profile*, *Search*, *Messages*, *Direct link*, *Other*. Rows with 0 % are omitted.
   Always shown (no privacy threshold — it is a property of the video, not of a person).
6. **Search queries** — up to 10 rows: the query and its share of search-sourced views. Shown only
   when at least one query qualifies (see §4.2). Empty state: *No search traffic yet*.
7. **Viewers** — window-scoped:
   - a two-tile row: **Total viewers** and **New viewers**, each with change vs the previous
     window (the shapes `/studio/analytics/viewers` already uses);
   - below the threshold: the "enough viewers" empty state and nothing else;
   - above it: three small breakdowns — *Viewer type* (New / Returning), *Followers* (Followers /
     Non-followers), *Age* (the seven groups) — each as a horizontal bar list with percentages of
     total viewers.

Visual language stays the current Studio one (`StudioCard`, `SectionTitle`, blue `#0075db` lines,
`text-black/50` captions).

**Charts are drawn with Recharts 3.x through shadcn's `chart` component** (owner's decision:
`npx shadcn add chart`, which adds `recharts` and `src/components/ui/chart.tsx` with
`ChartContainer`/`ChartTooltip`). The two hand-drawn SVGs (`MetricTabs`' day line and
`RetentionChart`) are replaced, not patched. TikTok's hover look maps onto Recharts directly:
`<Tooltip cursor={{ stroke, strokeDasharray }}>` for the dashed vertical cursor, `activeDot` for
the dot on the line, a custom `content` for the card (date + value, or time + "NN% still
watching"), and `<Area>` with a `<linearGradient>` for the faint fill under the line. Axis
ticks keep the current labels (`dayLabel`, `formatDuration`). `lib/studio/metrics.ts` keeps
`niceMax`, `changeLine`, `dayLabel`, `labelIndexes`; `linePath` goes away with the SVGs. The bar
lists (traffic source, viewer breakdowns) are plain Tailwind `div`s, no chart.

## 4. Data

### 4.1 Where a view came from — `source` and `searchQuery` on the watch session

The client knows where it rendered the card; the server cannot infer it. So the watch report
carries it end to end:

- `useWatchSession` gains `source: WatchSource` and `searchQuery?: string`.
  `WatchSource = "FOR_YOU" | "FOLLOWING" | "FRIENDS" | "EXPLORE" | "PROFILE" | "SEARCH" |
  "MESSAGE" | "DIRECT"`.
  - Feed pages pass their own: `/` → FOR_YOU, `/following` → FOLLOWING, `/friends` → FRIENDS.
  - `/video/{id}` reads `?src=` and `?q=` from its URL and passes them down to its `VideoCard`.
    The links that open it set `src`: search results → `search` + `q=<term>`, profile grid →
    `profile`, explore grid → `explore`, a video shared in a chat → `message`. No `src` (pasted
    link, share sheet, bookmark) → DIRECT. The page's prev/next navigation (`router.replace` in
    `VideoDetail.tsx`) carries `src`/`q` along, so the second video does not become DIRECT.
  - Studio's own post preview (`PostPreview`/`FeedPhone`) must not report watches at all; builder
    to confirm it does not today.
- `POST /interactions/videos/{id}/watch` body gains `source` (enum, **optional**) and `searchQuery`
  (optional, trimmed, lower-cased, max 100 chars; only meaningful with SEARCH, dropped otherwise).
  A missing `source` is stored as `OTHER`; a present but unknown value is a 400. Optional because
  the Flutter app (`tiktok_mobile`) still posts `{watchedMs, durationMs}` and is **out of scope**
  (owner's decision): its sessions must keep flowing into `interaction.watch-events`, which is
  also the ranker's training label. Mobile viewers show as *Other* in traffic source and
  *Unknown* in age until the app is updated.
- `VideoWatchEvent` gains `String source` and `String searchQuery` (both nullable; events produced
  before this field deserialise to null).
- ClickHouse `watch_events` gains `source LowCardinality(String) DEFAULT 'OTHER'` and
  `search_query String DEFAULT ''`. The consumer writes `OTHER` for a null source. Added with
  `ALTER TABLE … ADD COLUMN IF NOT EXISTS` in both `services/analytics-service/src/main/resources/
  schema.sql` (runs on every boot, so it must be idempotent) and `db/schema/clickhouse/
  analytics_db.sql`.
- Anonymous playback never reaches `/watch` (it needs a token), so traffic source covers signed-in
  viewers only, exactly like every other number on the page today.

### 4.2 Search queries

`SELECT search_query, count() … WHERE video_id = ? AND source = 'SEARCH' AND search_query != ''
GROUP BY search_query ORDER BY count() DESC LIMIT 10`, in the window. A query is listed only if it
has **at least 3 views** — my call, so a single person's search phrase is never shown to the
creator. Percentages are of all search-sourced views in the window, not of all views.

### 4.3 Saves

analytics-service consumes `interaction.save-events` (`VideoSavedEvent`) and writes
`engagement_events` rows with `event_type = 'SAVED'` / `'UNSAVED'`, mirroring the like consumer.
Saves are **net** (saved − unsaved), like likes and comments, in every query that counts
engagement: `dailyEngagement`, `engagementTotals` and therefore also the account overview and the
top-videos table, which gain a `saves` field as a side effect (the frontend may ignore it there).

### 4.4 Followers gained from a video

- `POST /users/{id}/follow` accepts an optional JSON body `{ "sourceVideoId": "…" }`. The frontend
  sends it from the feed's `ActionRail` (and the detail page's rail), which both know the card's
  video. Profile-page follows send nothing.
- `UserFollowChangedEvent` gains `Long sourceVideoId` (nullable; absent on old events and on
  unfollows).
- **Private creators** (owner's decision): a follow of a private account becomes a follow request
  and the event is only published on approval. `user_follow_requests` gains `source_video_id`
  (Flyway), `follow()` stores it, and `approve()`/`approveAll()` copy it onto the event, so their
  "New followers" tile works the same way.
- ClickHouse `follow_events` gains `source_video_id String DEFAULT ''`. `db/schema/clickhouse/
  analytics_db.sql` has no `follow_events` at all today; add its `CREATE` there with the column.
- Per video: `newFollowers = countIf(followed = 1 AND source_video_id = ?)` — gross, not net, like
  TikTok's "New followers" tile. Lifetime for the tile; windowed in `current`/`previous`.

### 4.5 Birth date

Owned by **user-service** (`user_profiles.birth_date DATE NULL`, Flyway `V10__…`). It is profile
data, lives next to the other profile fields, and user-service already materialises the profile
from `UserRegisteredEvent`.

Paths in:

1. **Email sign-up.** `CompleteSignupRequest` gains `@NotNull LocalDate birthDate`; the server
   repeats the age gate (≥ 13 on the day of sign-up, not in the future, not before 1900) and
   answers 400 `birthDate` otherwise. `UserRegisteredEvent` gains `LocalDate birthDate` (nullable);
   user-service's consumer stores it.
2. **Everyone else — social sign-in and accounts that predate the column.** `UserProfileResponse`
   gains `birthDate` (ISO date or null; **owner-only**, null on anyone else's profile — a birth
   date is private), the gateway's `GET /me` passes it through, and `PATCH /users/me` accepts
   `birthDate`, **only while
   the stored value is null**; once set it is immutable (409 on a second attempt). TikTok does not
   let people edit it freely either.
   The frontend, right after any successful sign-in (password or social), reads `/users/me` as it
   already does for profile-ready, and if `birthDate` is null shows one more modal step, *When's
   your birthday?*, reusing the three selects and `checkBirthday`. The step is the only thing in the
   modal at that point. Closing the modal is allowed (my call — we will not lock existing users
   out of their own accounts) and the step comes back on the next sign-in until it is answered.
3. **auth-service does not store it.** `SocialLoginResponse` is unchanged; "needs a birthday" is
   derived by the client from the profile, not flagged by auth.

Path to analytics: a new `UserBirthDateSetEvent(userId, birthDate)` on `user.profile-events` (new
topic, through user-service's existing outbox), published when the PATCH sets it. analytics-service
keeps one dimension table, `viewer_profiles (user_id Int64, birth_date Nullable(Date), updated_at
DateTime64(3)) ReplacingMergeTree(updated_at) ORDER BY user_id`, fed by both `UserRegisteredEvent`
(when `birthDate` is present) and `UserBirthDateSetEvent`.

Age = whole years between `birth_date` and the watch's `occurred_at`. Null → Unknown.

### 4.6 Viewer breakdowns, per video, in the window

Viewers = `uniqExact(user_id)` of the video's watches in the window, `user_id > 0`.

- **New vs returning**: *new* is exactly what `newViewersByVideo` already computes — a viewer
  whose first watch of any of this creator's videos is inside the window, **attributed to that
  first video only**. So a viewer who discovered the creator on video A and then watched B in the
  same window is new on A and returning on B. This keeps the page equal to the `newViewers` column
  of the top-videos table for the same video, and `total − new ≥ 0` always holds. (My call; the
  "new on both" reading was the alternative.) Returning = total − new.
- **Followers vs non-followers**: whether the viewer follows the creator **at the end of the
  window** — the latest `follow_events` row per `(follower_id, following_id = creator)` with
  `occurred_at < window.to`. `ponytail:` as-of-window-end rather than as-of-each-watch; the
  per-watch version is a correlated lookup and the difference is invisible at this scale.
  - `follow_events` only holds follows made since the consumer shipped (2026-10-23) and is not in
    the seed export, so every older follower would read as a non-follower. **Backfill (owner's
    decision)**: user-service gets a one-off command (the shape — admin endpoint or CLI runner — is
    the builder's) that publishes one `UserFollowChangedEvent` per live `user_follows` edge with
    `occurredAt` = the edge's `createdAt` and a **deterministic `eventId`** derived from the edge
    id, so re-running it is idempotent for ClickHouse's `ReplacingMergeTree`. It goes on its
    **own topic, `user.follow-backfill-events`**, read only by analytics-service: `user.follow-
    events` is also consumed by notification-service and chat-service, which would turn a backfill
    into thousands of "X followed you" notifications. The export under `~/seed-export/clickhouse/`
    gains `follow_events.native` and `viewer_profiles.native` (the export script already dumps
    every table in `analytics_db`).
- **Age groups**: join the window's distinct viewers to `viewer_profiles`; bucket as in §2. A
  viewer with several watches in the window is aged at their first in-window watch.
- All three come back as **counts**, not percentages; the client divides. All three are `null`
  when total viewers < `min-audience`.

### 4.7 Previous-window comparison

`GET /analytics/creator/videos/{id}` already computes the window; it now also runs the window
queries for `previousFrom … from` and returns both as totals. The client stops summing `daily`
itself and reads `current` (keeps the two in lockstep).

## 5. API

`GET /api/v1/analytics/creator/videos/{videoId}?days=` — fields added, nothing removed:

```json
{
  "videoId": "…",
  "views": 4999, "viewers": 1800, "totalWatchMs": …, "avgWatchMs": …, "completionRate": 0.39,
  "likes": 181, "comments": 55, "shares": 149,
  "saves": 23,
  "newFollowers": 12,
  "retention": [1, 0.98, …],
  "daily": [ { "day": "2026-11-04", "views": 10, "likes": 3, "comments": 0, "shares": 0,
               "saves": 1, "followersGained": 2, "followersLost": 0 } ],
  "current":  { "views": 4999, "likes": 181, "comments": 55, "shares": 149, "saves": 23, "newFollowers": 12 },
  "previous": { "views": 0,    "likes": 0,   "comments": 0,  "shares": 0,   "saves": 0,  "newFollowers": 0 },
  "trafficSources": [ { "source": "FOR_YOU", "views": 4100 }, { "source": "SEARCH", "views": 300 } ],
  "searchQueries":  [ { "query": "tiny house", "views": 120 } ],
  "viewers": {
    "total": 1800, "newViewers": 900, "previousTotal": 0, "previousNewViewers": 0,
    "breakdownAvailable": true,
    "returning": 900,
    "followers": 400, "nonFollowers": 1400,
    "ageGroups": { "13-17": 10, "18-24": 700, "25-34": 600, "35-44": 200, "45-54": 50, "55+": 10, "UNKNOWN": 230 }
  }
}
```

- `daily.followersGained` is no longer always 0 here: it is the day's follows attributed to this
  video (`followersLost` stays 0 — an unfollow has no source video).
- `trafficSources` carries only sources with views > 0, in no promised order; `source` is one of
  the eight client values or `OTHER`.
- `viewers.returning/followers/nonFollowers/ageGroups` are null when `breakdownAvailable` is false.
- `docs/tiktok-studio-api.md` §2.3 is updated with the above; §2.1/§2.2 note `saves`.

`POST /api/v1/interactions/videos/{id}/watch` — body gains `source` (required) and `searchQuery`.
`POST /api/v1/users/{id}/follow` — optional body `{ "sourceVideoId" }`.
`POST /api/v1/auth/register/complete` — body gains `birthDate` (required).
`PATCH /api/v1/users/me` — accepts `birthDate` once. `GET /users/me` returns it.

Frontend types in `src/lib/api/types.ts` follow the JSON above.

## 6. Non-goals and anti-goals

- **The Flutter app is out of scope.** The backend stays compatible with what it sends today (§4.1);
  the mobile watch source, the mobile birthday step and `birthDate` on the legacy `POST
  /auth/register` are a later feature.
- No gender, no country/city, no device or OS.
- No *Sound* or *Hashtag* traffic sources (the app has no sound pages; hashtags open search, so
  they count as SEARCH with the hashtag as the query).
- No hourly chart for a video's first 48 h; days stay the finest grain.
- No backfill of `source`, `search_query`, `source_video_id` or birth dates for existing rows.
  The only backfill is the follow graph (§4.6), because without it the follower split is wrong
  for every account that existed before 2026-10-23.
  Existing seed data (`~/seed-export/clickhouse/*.native`) must still import: the new columns
  have defaults, and the builder verifies a Native insert with the old column set still succeeds.
- Anti-goal: anything that lets a creator identify one viewer — hence the audience threshold and
  the 3-view floor on search queries. Do not "fix" an empty breakdown by lowering those.
- Recharts is the only chart library; no second one, and no canvas library (jsdom tests must
  still render the charts).

## 7. Constraints

- Both repos are on `feat/suggested-accounts` with uncommitted work; this feature goes on its own
  branch in each repo, cut from there.
- ClickHouse changes are additive `ALTER … IF NOT EXISTS` only; no table rebuilds.
- Kafka event records only gain nullable trailing fields; consumers stay tolerant of old payloads
  (the codebase's existing convention, see `UserRegisteredEvent.avatarUrl`). The pinned JSON in
  `event-schema/EventJsonCompatibilityTest` is extended, not rewritten, for each touched event.
- The 60 s memo in `CreatorAnalyticsServiceImpl` keeps keying by `(creatorId, videoId, days)`;
  the new sub-queries ride in the same memo entry.
- Days and windows keep using `Asia/Ho_Chi_Minh`.

## 8. Testing expectations

- Backend: unit tests for the new service maths (window totals, viewer buckets, age bucketing
  including the boundaries 13/18/25/35/45/55 and Unknown); repository tests against the ClickHouse
  test fixture in the style of `CreatorAnalyticsRepositoryTest`; consumer test for the save event;
  auth test for the birth-date gate; user-service tests for the once-only PATCH, for
  `sourceVideoId` surviving a follow request's approval, and for the backfill's deterministic
  `eventId`; a watch-endpoint test that a body without `source` is accepted as `OTHER`.
- Frontend: `StudioVideoAnalytics.test.tsx` grows cases for the insight line (largest drop,
  first-step case, hidden at 0 views), the Saves cell, the threshold empty state, and the traffic
  list omitting zero rows; a test that `useWatchSession` sends `source`/`searchQuery`; a sign-in
  test that the birthday step appears when the profile has none and not when it does.

## 9. Left to the builder

- Exact component split under `src/components/studio/` (one file or a `video/` folder).
- Recharts composition details (`ResponsiveContainer` sizing inside `StudioCard`, tooltip card
  markup, gradient ids). Recharts' `ResponsiveContainer` measures 0×0 in jsdom, so tests either
  pass fixed `width`/`height` or assert on the tooltip/insight text rather than on paths.
- How `?src`/`?q` ride through `BackendVideoDetail` → `VideoDetail` → `VideoCard` (props vs a
  small context).
- Whether `viewer_profiles` is written through the existing `BatchWriter` or directly.
- Flyway version numbers, Kafka topic configuration (partitions/retention) for
  `user.profile-events`, and the OpenAPI/doc wording.
- Order of implementation across the two repos, as long as the backend contract lands first.
