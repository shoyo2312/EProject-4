# User Settings — storage, contract and enforcement

Implementation spec for the account-level settings behind the web client's `/setting` page.
English, like `ARCHITECTURE.md` and `moderation.md`: this is an internal build document, not a
client contract.

**Status: implemented.** The client-facing half now lives in `user-service-api.md` §3.14–3.15,
§4 and §5; the comment-audience half in `interaction-service-api.md` §3.4 and §5. What follows is
the design record, kept in step with the code — where the implementation decided something this
document had left open, the paragraph says so.

## 0. Where this sits

There is no settings or preferences concept anywhere in the backend today. A grep for
`preference|setting|user_settings` across all fifteen services returns nothing but
`TooManyProfileIdsException` and `recommendation-service`'s `UserMuteClient`. `UserProfileResponse`
carries `userId, username, displayName, bio, avatarUrl, followerCount, followingCount` and nothing
else. Every switch on `/setting` is therefore inert, and the web client renders it that way on
purpose rather than pretending to save.

This document covers the tier that is **one table, two public endpoints and one internal
endpoint**. It deliberately stops short of:

- **Private account** — built on its own, see
  `docs/superpowers/specs/2026-09-26-private-account-design.md`. The switch is written through
  `PATCH /me/settings` like the rest, but the flag lives on `user_profiles` (V11) because every
  viewer reads it; follow requests are `user_follow_requests` (V12).
- **Data export** — built on its own (`user-service-api.md` §3.21). Business verification was
  dropped from the page.

**Notification switches, screen time and Filter keywords** arrived later
(`docs/superpowers/specs/2026-10-24-settings-dead-rows-design.md`), all stored here: V18–V22 in §1,
their endpoints in §2.3–2.4, and where each is enforced in §4.3.

Those are listed so nobody reads this document as the whole of `/setting`.

The **comment filters** (spam/offence switch, hold everything, keywords) were deferred here too and
arrived later with text moderation Phase 2
(`docs/superpowers/specs/2026-10-14-text-moderation-phase2-design.md`): stored in this table (V15),
written through `PATCH /me/settings` like the rest, enforced by interaction-service — §4.1b.

**Ads are not deferred, they are dropped.** The seven ads rows have been removed from the client's
settings data outright rather than left inert, so there is no ads section on the page and no
`offsite*` fields here. There is no ads subsystem anywhere in this backend, and storing two
booleans for a product that does not exist is a column nobody can ever read. If advertising is
ever built, its settings arrive with it.

## 1. Storage

New table in user-service, **not** new columns on `user_profiles`. `GET /users?ids=` batches
profiles a hundred at a time on hot paths; widening that row and that DTO to carry settings only
the owner ever reads is the wrong trade.

`V10__create_user_settings_table.sql`:

```sql
CREATE TABLE user_settings (
    id                          BIGINT       PRIMARY KEY,
    user_id                     BIGINT       NOT NULL,

    comment_audience            VARCHAR(16)  NOT NULL DEFAULT 'EVERYONE',
    dm_audience                 VARCHAR(16)  NOT NULL DEFAULT 'EVERYONE',

    screen_time_limit_minutes   INTEGER,
    sleep_hours_start           TIME,
    sleep_hours_end             TIME,
    weekly_screen_time_digest   BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    deleted_at                  TIMESTAMPTZ,
    version                     BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_settings_user_id ON user_settings (user_id) WHERE deleted_at IS NULL;
```

Same shape as `user_blocks` (V5): Snowflake `id`, audit columns, soft delete, `version`. The entity
extends `BaseEntity` with `@SuperBuilder`, like every other one.

**No row is written at registration.** Profiles are created asynchronously off
`UserRegisteredEvent`, and `user-service-api.md` already has to warn that
`USER_PROFILE_NOT_FOUND` can mean "your profile has not been created yet". A second row with the
same race would be a second such warning. Instead:

- **read** with no row returns the defaults above, `200`, never `404`;
- **write** with no row inserts one carrying the patch applied to the defaults.

This also means the defaults live in exactly one place that matters — the `DEFAULT` clauses and the
entity's field initialisers must agree, so state them once in the entity and let the migration
mirror it.

`V15__comment_filters.sql` adds the creator's comment filters (§4.1b), its `DEFAULT`s mirroring
`UserSettings.defaultsFor()` the same way:

```sql
ALTER TABLE user_settings
    ADD COLUMN filter_spam_offensive BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN filter_all_comments   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN comment_keywords      TEXT[]  NOT NULL DEFAULT '{}';
```

The keywords are an array column, not a child table: at most fifty short strings, always read and
replaced whole, never queried by element.

The dead-rows work added, again with `DEFAULT`s mirroring `defaultsFor()`:

- **V18** — seven notification switches, all `BOOLEAN NOT NULL DEFAULT TRUE`: `push_likes`,
  `push_comments`, `push_new_followers` (Push notifications → Interactions) and `in_app_likes`,
  `in_app_comments`, `in_app_new_followers`, `in_app_reposts` (In-app notifications). Plus
  `time_zone VARCHAR(64)`, the browser's IANA zone. It also rewrote every stored `sleep_hours_end`
  to start + 8h, the rule from then on.
- **V19** — `screen_time_days`: one row per account and local date, `day_seconds` (06:00–22:00) and
  `night_seconds` (22:00–06:00), unique on `(user_id, local_date)` while not deleted.
- **V20** — the daily limit's second shape and its passcode: `screen_time_limits_by_day INTEGER[]`
  (Monday first), `screen_time_passcode_hash VARCHAR(60)` (bcrypt),
  `screen_time_passcode_failures`, `screen_time_passcode_locked_until`. It turned off every limit
  saved before passcodes existed.
- **V21** — `feed_keyword_filters`: one row per keyword (`keyword VARCHAR(200)`, `for_you`,
  `following`, `friends`), unique on `(user_id, lower(keyword))` while not deleted. A child table,
  unlike the comment keywords, because each row carries its own feeds and is edited one at a time.
- **V22** — `last_digest_week DATE`, the weekly digest's claim (§4.3).

**`time_zone` and `last_digest_week` are read-only on the entity** (`insertable = false,
updatable = false`). Each has exactly one writer, a native statement (the heartbeat's upsert, the
digest's claim), and neither bumps `version`. A `PATCH /me/settings` that read the row before one of
them ran writes back every column it maps; if it mapped these, it would put the old zone back, or
reopen a week already sent. Both cases are pinned by tests.

## 2. Public contract

Base path `/api/v1/users`, both endpoints require a Bearer token like everything else in
user-service.

### 2.1 `GET /me/settings`

`200 OK`, `data` → `UserSettingsResponse`:

```json
{
  "commentAudience": "EVERYONE",
  "dmAudience": "EVERYONE",
  "screenTimeLimitMinutes": null,
  "sleepHoursStart": null,
  "sleepHoursEnd": null,
  "weeklyScreenTimeDigest": true,
  "filterSpamOffensive": true,
  "filterAllComments": false,
  "commentKeywords": [],
  "pushLikes": true,
  "pushComments": true,
  "pushNewFollowers": true,
  "inAppLikes": true,
  "inAppComments": true,
  "inAppNewFollowers": true,
  "inAppReposts": true,
  "timeZone": "Asia/Ho_Chi_Minh",
  "screenTimeLimitsByDay": null,
  "hasScreenTimePasscode": false
}
```

`timeZone` is null until the web has sent a screen-time heartbeat. The passcode itself is
write-only; `hasScreenTimePasscode` says whether one is set.

A **flat keyed object**, not the section/group tree the page draws. Layout is presentation and
stays in the client; this is the values. Getting that boundary wrong is what forces bespoke wiring
per row.

`null` on the screen-time fields means "off" — there is no separate enable flag, and a limit of
`0` is a validation error rather than a second way to say off. The daily limit has two shapes, the
same limit every day (`screenTimeLimitMinutes`) or one per weekday (`screenTimeLimitsByDay`, seven
values, Monday first); at most one is set, and both null is off.

`sleepHoursStart`/`sleepHoursEnd` are **wall-clock times**, stored `TIME` and carried as
`LocalTime`, on the wire as `"HH:mm"` in both directions (`@JsonFormat`, minute precision). As on
TikTok, sleep hours always last eight hours: the end is derived from the start on every write and
is no longer accepted in a PATCH. A time that does not parse is the body parser's `BAD_REQUEST`,
not `VALIDATION_ERROR`; the enums are `@Pattern`-checked instead precisely so an unknown audience
names all three values.

The account's zone is `timeZone`, the browser's IANA zone as the last screen-time heartbeat sent it
(§2.3). It is what notification-service reads sleep hours in (§4.3). Until there is one, sleep hours
mute nothing on the server, and the web, which mutes its own notifications in the browser's local
time, is the only thing honouring them.

### 2.2 `PATCH /me/settings`

`200 OK`, returns the full `UserSettingsResponse` as it now stands.

```json
{ "commentAudience": "FRIENDS" }
```

PATCH semantics: **an absent key leaves the field alone**. `UpdateUserSettingsRequest` is
therefore a class with hand-written setters rather than a record like every other request DTO
here: Jackson calls a setter only for keys the JSON carries, and that is the only reliable way to
tell an absent key from an explicit `null` — a record component is `null` for both, and `Optional`
does not help, since a missing creator parameter is filled with the deserializer's absent value,
which for `Optional` is the same `Optional.empty()` an explicit null produces. An explicit `null`
on one of the NOT NULL fields is a `VALIDATION_ERROR` rather than a silent no-op. Unlike `PATCH /users/me` there is no
empty-string-clears trap here — every field is a boolean, an enum, a nullable scalar or the keyword
list (cleared with `[]`), and an explicit `null` on a nullable field *is* the way to clear it. That difference is worth a line in
the client doc, because the two endpoints sit next to each other and behave differently on purpose.

Validation:

- `commentAudience`, `dmAudience` ∈ `EVERYONE | FRIENDS | NO_ONE` → `VALIDATION_ERROR` (400)
- `screenTimeLimitMinutes` ∈ `[10, 480]` or null; `screenTimeLimitsByDay` exactly seven values in
  the same range, or null; both non-null after the merge is a `VALIDATION_ERROR`
- `screenTimePasscode` — write-only, exactly four digits. Turning the limit on with no stored
  passcode and none in the patch is a `VALIDATION_ERROR` naming it; changing the limit keeps the
  stored one; sending one while the limit is on replaces it; turning the limit off forgets it (and
  its wrong-guess count and lock), which is how a forgotten passcode is replaced
- `sleepHoursStart` — a start, or null to turn sleep hours off; the end follows. A `sleepHoursEnd`
  key from an older client is ignored like any unknown key
- the seven notification switches — booleans; `null` is a `VALIDATION_ERROR`
- `filterSpamOffensive`, `filterAllComments` — booleans; `null` is a `VALIDATION_ERROR`
- `commentKeywords` — the **whole list**, replacing the stored one; `[]` clears it, `null` is a
  `VALIDATION_ERROR`. At most 50. Each is sanitized like comment text (`TextSanitizer.sanitizeLine`:
  invisible characters dropped, whitespace collapsed, one line) **in the setter, before validation**,
  so the 1–30 limit counts the code points that get stored. Each must have a letter or a digit
  (`Blocklist.isMatchable`): a keyword of only emoji or punctuation could never match, and the
  creator would believe it held something. Duplicates are dropped by the key the matcher reads them
  by (`Blocklist.keyOf`), first spelling kept — "Spoiler" and "spoiler" are one keyword, "tạch" and
  "tach" stay two because they match different text

Optimistic locking is inherited from `BaseEntity`, so two devices writing at once produce the
existing `CONCURRENT_MODIFICATION` (409) — the one 409 the client is told to retry.

Two devices writing for the *first* time is the same answer by a different mechanism: both read no
row, both insert, and `uq_user_settings_user_id` stops the second. That is the check-then-act race
`FollowServiceImpl.follow` already handles, so the insert is a `saveAndFlush` and the violation is
translated to the same 409 rather than escaping as a 500 — the caller's move is identical either
way, and the retry finds the row and updates it.

### 2.3 Screen time

Under `/api/v1/users/me/screen-time` (`ScreenTimeController`):

- `POST /heartbeat` `{ "seconds": 60, "timeZone": "Asia/Ho_Chi_Minh" }` → `200`. The web sends one
  about a minute while the tab is visible and focused. `seconds` (≥ 1) is clamped to 120 — a
  client can only inflate its own numbers, so a clamp is all the trust needed. The seconds land on
  the local date and the half of the day (06:00–22:00 day, the rest night) of the moment the
  server receives them, in `timeZone`, which is also stored on the account. One upsert per day adds
  to the totals, so two tabs or a retried heartbeat never lose a count. An unknown zone is a
  `VALIDATION_ERROR` and records nothing.
- `GET ?weekStart=YYYY-MM-DD` → `{ weekStart, days: [{ date, daySeconds, nightSeconds }] }`, seven
  days Monday first, zero-filled. `weekStart` must be a Monday (`VALIDATION_ERROR` otherwise).
- `POST /passcode/verify` `{ "passcode": "1234" }` → `{ "correct": true }`. No passcode set reads
  as wrong. The fifth wrong answer in a row locks checking for 15 minutes:
  `TOO_MANY_PASSCODE_ATTEMPTS` (429) with `Retry-After` in seconds. The count lives on the row
  (user-service has no Redis), and the version column serializes a burst: a request that loses
  the race is `CONCURRENT_MODIFICATION`, never an answer, so twenty parallel guesses still get at
  most five wrong answers. Not four digits is a `VALIDATION_ERROR` and is not counted.

### 2.4 Filter keywords

Under `/api/v1/users/me/keyword-filters` (`FeedKeywordFilterController`): `GET` (newest first),
`POST` (`201`), `PUT /{id}`, `DELETE /{id}` (soft delete; the keyword can be added again). A filter
is `{ id, keyword, forYou, following, friends }`.

- The keyword is sanitized like a title, then must be one word or one hashtag (`^#?[^\s#]+$`), at
  most 200 code points, and have something to match (`Blocklist.isMatchable`) — `VALIDATION_ERROR`
  otherwise; at least one feed, likewise. The spelling typed is kept.
- `KEYWORD_FILTER_DUPLICATE` (409) when the account already filters it in any capitalization — a
  check by `lower(keyword)` backed by the unique index, which also turns a lost race into the same
  409. `TOO_MANY_KEYWORD_FILTERS` (409) past 100 (a check, not a lock: two parallel creates can
  reach 101). `KEYWORD_FILTER_NOT_FOUND` (404) for an id the caller does not own.

## 3. Internal contract

### 3.1 `GET /api/v1/users/internal/settings?ids=1,2,3`

The path prefix is **not** cosmetic. `api-gateway`'s `SecurityConfig` line 44 already carries
`.pathMatchers("/api/v1/users/internal/**").denyAll()`, first in the chain so no later rule can
open it, and user-service's own `SecurityConfig` admits the same prefix to any caller carrying the
internal API token (`X-Internal-Token`) and no one else. Putting the endpoint there means **no
gateway change at all** and no user token — which is mandatory, because chat-service asks these
questions from WebSocket frames that carry no Authorization header. An
endpoint at `/users/{id}/settings-policy` instead would need a new deny rule, and forgetting it
publishes every account's settings.

Precedents to copy: `GET /api/v1/users/internal/blocks` (`BlockController`) and
`GET /api/v1/users/internal/{userId}/muted-ids` (`MuteController`).

`200 OK`, `data` → list of:

```json
{
  "userId": 123,
  "commentAudience": "EVERYONE",
  "dmAudience": "EVERYONE",
  "filterSpamOffensive": true,
  "filterAllComments": false,
  "commentKeywords": [],
  "pushLikes": true,
  "pushComments": true,
  "pushNewFollowers": true,
  "sleepHoursStart": "22:00",
  "sleepHoursEnd": "06:00",
  "timeZone": "Asia/Ho_Chi_Minh",
  "forYouKeywords": ["cat"],
  "followingKeywords": [],
  "friendsKeywords": []
}
```

Only the fields another service enforces: the two audiences; the comment filters video-service
relays to interaction-service (§4.1b); what notification-service checks before a phone push — the
push switches, sleep hours and the zone they are read in; and the Filter keywords video-service
drops from each feed. The three keyword lists are never null, empty when nothing is filtered. The
daily limit, its passcode, the in-app switches and the digest's claim stay in user-service: nobody
else enforces them, and an internal endpoint that hands them over anyway is a privacy leak waiting
for its first careless caller. Both kinds of keywords are things an account may not want read, so
they travel only on internal paths: this one, and video-service's `/policy`, both denied at the
gateway and both behind the internal token.

**An id with no settings row comes back carrying the defaults — it is never omitted.** This is the
opposite of `GET /users?ids=`, which drops profiles the viewer cannot see. Omitting here would make
a caller's "no answer" indistinguishable from "no restriction", and the first such caller to treat
absence as permission has silently disabled the feature. Cap `ids` at 100, reusing
`UserProfileService.MAX_BATCH_IDS` (line 14) and `TooManyProfileIdsException`.

Tier 2 only ever passes a single id. It is specified as a batch because the tier-3 feed filter will
need one and it costs nothing to build it that way now.

### 3.2 `GET /api/v1/users/internal/friendship?userA=1&userB=2`

**New, and easy to miss.** `FRIENDS` on either audience needs a mutual-follow check, and the
existing one is not reachable from the callers that need it:

- `GET /users/{userId}/friendship` requires a JWT. video-service's `FriendshipClient` copes by
  forwarding the viewer's inbound bearer token off the thread-bound request context.
- chat-service has no token to forward. `ConversationServiceImpl.getOrCreate` runs for WebSocket
  frames, which is exactly why `BlockClient` uses the tokenless internal endpoint.

So `FRIENDS` DM audience is unimplementable without this. It is a thin wrapper over
`FollowServiceImpl.friendship`, which is already edge-only and already answers `false` for a
non-existent user rather than 404.

`200 OK`, `data` → the existing `FriendshipResponse`, so `{ "userId": 2, "friends": true }` — the
same shape the authenticated endpoint answers, rather than a second DTO carrying one field.

It lives in its own `InternalFollowController` because `FollowController` is mapped under
`/api/v1/users/{userId}`, which cannot also produce a literal `/internal/` path. Worth knowing:
`/api/v1/users/internal/friendship` **also matches** `/{userId}/friendship`, and only Spring
ranking a literal segment above a path variable sends it to the tokenless mapping. If that ever
stopped holding, chat-service would get 401s instead of answers — `UserSettingsControllerTest`
pins it.

### 3.3 `GET /api/v1/users/internal/relationships?viewerId=1&userIds=2,3,4`

story-service decides which of an owner's stories a viewer may see from four facts about the pair:
blocked, private account, follows, mutual follows. `/internal/blocks` and `/internal/friendship`
answer one pair each, so one screen of 100 avatars cost 200 sequential calls. This answers a whole
page in at most four queries, however many ids it carries — none of them per id.

`viewerId` is optional: without it the viewer is anonymous, and a guest reads stories too. Same
prefix, same guard as 3.1 — denied at the gateway, internal token at user-service — so there is
nothing to add to either `SecurityConfig`. `/internal/blocks` and `/internal/friendship` stay:
`/internal/blocks` is called by chat-, interaction-, notification- and video-service, `/internal/friendship`
by chat- and interaction-service.

`200 OK`, `data` → one entry per distinct non-null id, in no promised order:

```json
{ "userId": 2, "blocked": false, "privateAccount": true, "following": true, "friends": false }
```

- `blocked` — a live block in either direction (`findBlockedIdsAmong`).
- `privateAccount` — the live profile is private, as `friendship()` reads it. A closed account is not
  singled out: story-service hides closed owners from its own record of closures.
- `following` — the viewer follows the id (`findFollowingIdsAmong`).
- `friends` — the viewer follows the id **and** the id follows the viewer. Only the ids the viewer
  follows are asked about, so `friends` implies `following`; someone who follows the viewer without
  being followed back is neither.

Edge-only, like 3.2: an id with no account is all false and still present in the answer, and the
viewer's own id is false for `blocked`, `following` and `friends` (`privateAccount` is still
reported). An anonymous viewer skips the block and follow queries altogether; an empty list skips
every query.

More than 100 ids is `400 TOO_MANY_PROFILE_IDS`, counted on the list as sent, before the distinct
(`UserProfileService.MAX_BATCH_IDS`, as in `followingAmong`). story-service's `FriendshipClient`
chunks to that size and treats a chunk it cannot get an answer for as private and not followed,
which shows the viewer none of those owners' stories.

## 4. Enforcement

Storing the values is the easy half. These are the parts that change behaviour.

### 4.1 Comment audience

Enforced in **interaction-service**, `CommentServiceImpl.create`, at the existing guard:

```java
if (videoOwnershipClient.areCommentsDisabled(videoId)) {
    throw new CommentsDisabledException(videoId);
}
```

**Widen `VideoPolicyResponse` rather than adding a user-service call to interaction-service.**
interaction-service has exactly one client today (`VideoOwnershipClient`) and already reads
`GET /videos/{id}/policy` on this path. Adding `commentAudience` to that response costs zero extra
round trips.

video-service resolves the owner's audience by calling `GET /users/internal/settings?ids={ownerId}`
behind a short-TTL Redis cache — `VideoCache` is the precedent for Redis in that service. **Do not
denormalise `commentAudience` onto the video document.** Denormalisation is the right answer for
the tier-3 feed filter, where the query cannot call per row; here `/policy` is already a
per-request lookup, so a cached call is O(1) and always near-fresh, whereas denormalising means
rewriting every document a creator owns each time they flip one setting.

Resulting precedence, top to bottom:

| Condition | Outcome |
|---|---|
| `video.commentsDisabled` | `COMMENTS_DISABLED` (403) — existing code, unchanged |
| `commentAudience == NO_ONE` | `COMMENTS_NOT_ALLOWED` (403) — **the owner included** |
| commenter is the video owner | allowed |
| `commentAudience == FRIENDS` and not mutual | `COMMENTS_NOT_ALLOWED` (403) |
| otherwise | allowed |

The owner row sits **below** the switch, not above it as an earlier draft of this table had it.
The switch already refuses the owner today, and "existing code, unchanged" on that row is the
stronger commitment: an owner exemption above it would quietly change shipped behaviour, letting
a creator comment on a video whose comments they had turned off. The exemption is from the
audience rules, which is what the test checklist below asks for.

`NO_ONE` was later moved **above** the owner row as well: an account open to no one reads as
comments off on every video it owns — the same as the per-video switch, owner included — and
video-service says so on every `VideoResponse` (`accountCommentsOff`, with `commentCount` withheld)
so clients hide the box and the count without asking. The owner is exempt from `FRIENDS` only.

The per-video switch and the account default are **both** restrictions, never an override in
either direction: a video with comments off stays off however permissive the account setting is,
and an account set to `NO_ONE` closes videos whose own switch was never touched.

A distinct code for the new case matters. `COMMENTS_DISABLED` means "the owner closed this video"
and the client says so; the audience case means "not you", and reusing one code would have the UI
tell friends-only viewers that a thread they can see is closed to everyone.

`FRIENDS` needs a **new `FriendshipClient` in interaction-service**, copied from video-service's:
same token-forwarding off the request context, same fail-closed default. Unlike chat-service,
interaction-service does have the caller's token — comment creation is an authenticated REST POST.

An audience name interaction-service cannot map — a user-service deployed ahead of it — is also
treated as `EVERYONE`, with a warn. That is the same fail-open stance as an unreachable lookup,
and the opposite of what chat-service does with the same input (§4.2).

**Fail-open, consistent with the existing guard.** `areCommentsDisabled` already fails open by
design — "an unreachable video-service lets the comment through rather than blocking every comment
site-wide". Audience follows it. Note this is the opposite of chat's block check, which fails
closed; the asymmetry is deliberate and both call sites say so. Say it again in the new code, or
somebody will "fix" one of them.

### 4.1b Comment filters

Also enforced in **interaction-service**, by `CommentScreening`, on every comment posted under one
of the account's videos. It runs after the audience check above, so a commenter the audience
refuses never reaches the filters. `moderation.md` ("Comments: held for the owner") has the full
order the text goes through: the rules, then these filters, then the classifier.

| Setting | Someone else's comment |
|---|---|
| `filterAllComments` | held, whatever it says (`CREATOR_FILTER`, rule `all`) |
| a `commentKeywords` entry in it | held (`CREATOR_FILTER`, rule `term:<keyword>`) |
| `filterSpamOffensive = false` | no longer held for profanity, insults or spam |

- **Held, never refused.** A filtered comment is stored `HELD` for the owner's queue
  (`interaction-service-api.md` §3.4b), and its author is told so. Holding is not a strike: strikes
  come only from a rule refusing a comment and from an admin removing one.
- **The owner's own comments are never held**, because they are the one who approves.
- **Keywords match like the blocklist** (moderation-lib `KeywordFilter`). They match whole
  syllables, so "spoiler" does not match "spoilers". A keyword written with diacritics matches only
  the same diacritics; one written without them matches every spelling.
- **`filterSpamOffensive = false` waives three categories and nothing else.** Hate, sexual content,
  threats, scams and personal information still hold, and a REJECT is still refused, because those
  protect the people reading the thread, not the creator's taste. The flags stay on the comment, so
  the admin comment list shows what a waiver let through.

Relay: `/internal/settings` (§3.1) → video-service `/policy` (`filterSpamOffensive`,
`filterAllComments`, `commentKeywords`) → interaction-service `CommentPolicy`. This is the round trip
§4.1 already pays; nothing is added. video-service caches the owner's audience and filters together,
as JSON under `user-settings:comment-policy:{id}`. The older `comment-audience:` keys are never read
again and expire on their TTL.

**Fails open to the defaults**, like the audience. A field user-service did not send (it was
unreachable, or predates V15) is relayed as null, and interaction-service reads a null as that
filter's default: spam and offence filtered, nothing else held, no keywords. Holding every comment
on the platform during a user-service outage would be the guard failing closed.

### 4.2 DM audience

Enforced in **chat-service**, `ConversationServiceImpl.getOrCreate`, immediately after the existing
`blockClient.requireNotBlocked(currentUserId, otherUserId)` on line 30.

New `SettingsClient`, modelled on `BlockClient` — tokenless, `downstream.user-service-uri`, timeouts
from `RestClientConfig`:

| Condition | Outcome |
|---|---|
| `dmAudience == EVERYONE` | allowed |
| `dmAudience == FRIENDS` and not mutual (§3.2) | `MESSAGING_NOT_ALLOWED` (403) — new |
| `dmAudience == NO_ONE` | `MESSAGING_NOT_ALLOWED` (403) |
| user-service unreachable | `DM_SETTINGS_UNAVAILABLE` (503) — new, fails **closed** |
| an audience name chat cannot map | `DM_SETTINGS_UNAVAILABLE` (503), with a warn |

Failing closed matches `BlockCheckUnavailableException` right above it: chat already pauses rather
than guess, and a privacy setting is not the place to start guessing.

**Enforced when a conversation is created only — not per message, and not on a reopen.** An
already-open conversation keeps working when the other side tightens their setting — the same shape
as "existing followers won't be affected" on private account. `getOrCreate` therefore looks the
pair's conversation up before it asks for the audience, and hands back one that exists without
consulting it: `POST /conversations` is also how a client reopens a thread (the Message button on a
profile), and checking first refused that for a thread the inbox still sent in. Enforcing per
message would also put a user-service call on the hot send path. `MessageServiceImpl` keeps its
block check and gains nothing; the block check in `getOrCreate` still runs first, reopen or not.

### 4.3 Enforcement of screen time and notifications

| Setting | Enforced by | How |
|---|---|---|
| Push switches (likes, comments, new followers) | notification-service | `NotificationServiceImpl.push` asks `/internal/settings` before FCM; the inbox entry is written regardless |
| Sleep hours | notification-service + web | no FCM push while the recipient's local time (`timeZone`) is in `[start, start + 8h)`; the web mutes its desktop notifications and shows two reminders |
| In-app switches | web | a banner for a `NotificationFrame` only when its switch is on |
| Daily limit + passcode | web | an overlay at the limit, dismissed for the day by `/passcode/verify`; never shown without a passcode set |
| Weekly screen time updates | user-service → notification-service | the digest job below |
| Filter keywords | video-service | §4.3b |

Which notification each switch governs: likes = `LIKE`, `COMMENT_LIKE`; comments = `COMMENT`; new
followers = `NEW_FOLLOWER`, `FOLLOW_REQUEST`, `FOLLOW_REQUEST_ACCEPTED`; reposts (in-app only) =
`REPOST`. `SHARE`, `STORY`, `SYSTEM` and `REPOST` have no push switch and always push; `SCREEN_TIME`
never pushes and never shows a banner — it is inbox only. Sleep hours mute every push.

**Push is skipped when user-service cannot say what the recipient chose** — unreachable, or an
answer without the id. That is the opposite of every fail-open guard above, on purpose: the inbox
already holds the notification, and pushing what the recipient switched off is the worse failure.
notification-service only asks when the recipient has a device to push to.

**The weekly digest** (`WeeklyScreenTimeDigest`, hourly, `user.screen-time.digest-cron`, `-` turns
it off) sends one `WeeklyScreenTimeSummaryEvent` per account and week: the switch on, a zone known,
and Monday 09:00 passed in that zone. It claims the week with
`UPDATE … SET last_digest_week = :week WHERE last_digest_week IS NULL OR last_digest_week < :week`
in the same transaction as the outbox row, so two instances running at the same minute send it
once, and a crash between the two leaves neither. A week with no time recorded is claimed and not
sent. notification-service writes one `SCREEN_TIME` entry per event ("Last week you spent 3h 20m on
Nowaa, a daily average of 28m."), claimed by `eventId`, referencing the week's Monday.

### 4.3b Filter keywords

video-service drops the videos whose title, description or tags match one of the viewer's keywords
for the feed being read — matched by moderation-lib `KeywordFilter` like a creator's comment
keywords, so case and diacritics behave the same; a leading `#` is dropped, so `#cat` filters the
tag `cat`. Only the reads that name a feed are filtered: `GET /videos/batch?feed=FOR_YOU` (the
ranked For You), `GET /videos/feed?feed=FOR_YOU` (its chronological fallback) and
`GET /videos/feed/following?feed=FOLLOWING|FRIENDS` (`FOLLOWING` when absent). A profile or a share
link is not a feed. The keywords ride in the same cached settings entry as the comment policy, so a
change shows within that cache's TTL (3s). A shortened page keeps its cursor. Anonymous reads, and
any read while user-service cannot answer, are served unfiltered: this is a preference, not a
safety control, and an empty feed is the worse answer.

### 4.4 Explicitly not server-side: colour contrast

`Accessibility → Increase color contrast` stays in the web client's `localStorage`. The row's own
copy scopes it to "your computer", it is already shipped that way, and moving it server-side buys
cross-device sync at the cost of a write per toggle plus a reconciliation story between the stored
value and the local one. Revisit only if someone asks for it by name.

## 5. Events

**One:** `WeeklyScreenTimeSummaryEvent` on `user.screen-time-summary-events`, keyed by `userId`,
through user-service's outbox (aggregate `SCREEN_TIME_SUMMARY`), consumed by notification-service
(§4.3). Settings changes themselves still publish nothing; the rest of this section is why.

Tier 2 needed no new Kafka event and no `libs/event-schema` addition.

The only consumer of a settings change outside user-service is video-service's policy cache, and a
short TTL handles that — a comment audience that takes up to a minute to tighten is not a
correctness problem, and the outbox row plus a consumer plus the ordering question is a lot of
machinery to avoid one minute of staleness.

The TTL was later cut from 30s to 3s: once every `VideoResponse` carried `accountCommentsOff`, a
stale entry became something the owner sees right after changing the setting, and half a minute of
it read as a bug. An event would not have been faster — user-service's outbox polls every 5s.

The comment filters (V15) ride in the same cache entry, so they take the same few seconds to apply
and need no event either.

`UserSettingsChangedEvent` becomes genuinely necessary in tier 3, when private-account state has to
be denormalised onto video documents and the search index. Add it then, through the existing
`OutboxPublisher`/`UserEventPublisher` pair in user-service, keyed on `userId` so one account's
changes stay ordered on one partition.

## 6. Error codes to add

| `code` | HTTP | Service | When |
|---|---|---|---|
| `COMMENTS_NOT_ALLOWED` | 403 | interaction-service | Audience refuses this commenter (§4.1) |
| `MESSAGING_NOT_ALLOWED` | 403 | chat-service | Audience refuses this sender (§4.2) |
| `DM_SETTINGS_UNAVAILABLE` | 503 | chat-service | Settings lookup failed; fails closed |
| `TOO_MANY_PASSCODE_ATTEMPTS` | 429 | user-service | Five wrong daily-limit passcodes in a row; `Retry-After` (§2.3) |
| `KEYWORD_FILTER_DUPLICATE` | 409 | user-service | The account already filters that keyword (§2.4) |
| `TOO_MANY_KEYWORD_FILTERS` | 409 | user-service | More than 100 keyword filters (§2.4) |
| `KEYWORD_FILTER_NOT_FOUND` | 404 | user-service | No such filter on the caller's account (§2.4) |

`VALIDATION_ERROR`, `CONCURRENT_MODIFICATION` and `TOO_MANY_PROFILE_IDS` are reused as-is. The
comment filters add no code. A bad keyword list is a `VALIDATION_ERROR`, and a filtered comment is
not an error at all: it is a `201` with `status: HELD`. All three
new codes need client copy in the web client's `lib/api/errors.ts` `MESSAGES` map, which is where
user-facing wording lives — the contracts are explicit that the server's own `message` is not shown.

## 7. Rate limiting

The gateway's 20 req/s is keyed on **IP**, and the web client reaches it through a Next.js rewrite,
so every browser viewer shares the Next server's address — `next.config.ts` already warns about
this. A settings PATCH is cheap enough not to care. Nothing here needs a per-account limit; the
endpoints that will are `data-export` and `business-verification` (deactivate/delete shipped in
auth-service — see `superpowers/specs/2026-10-17-account-deactivation-design.md`), none of
which are in this tier.

## 8. Test checklist

user-service:
- read with no row → defaults, `200`, and no row created as a side effect
- write with no row → row inserted with patch-over-defaults
- absent key leaves the field; explicit `null` clears a nullable one
- each enum rejects an unknown value; `screenTimeLimitMinutes` boundaries at 9/10/480/481; the
  per-day list needs seven values in range; both shapes at once refused
- passcode: needed to turn the limit on, kept when the limit changes, forgotten when it is turned
  off; stored hashed; five wrong in a row lock for 15 minutes, a right one resets the count, and a
  burst of twenty wrong guesses gets at most five wrong answers
- sleep hours: the end is always start + 8h (23:30 → 07:30); clearing the start clears both; a
  stale `sleepHoursEnd` key is ignored
- notification switches: on by default, each saves without touching the others, an explicit
  `null` is refused, and the internal policy carries the push ones with sleep hours and the zone
- heartbeat: day/night split at 06:00 and 22:00 local, local date after midnight, the 120s clamp,
  twenty parallel heartbeats add up exactly, the zone is stored and a stale settings save cannot
  undo it
- keyword filters: one word or hashtag, 200 code points, case-insensitive duplicates, the 101st,
  someone else's id is not found, a deleted keyword can be added again, and the internal policy
  carries the per-feed lists
- digest: due from Monday 09:00 local, once per week even across two concurrent runs, a zero week
  is claimed and not sent, and a stale settings save cannot reopen a week
- internal settings: unknown id returns defaults rather than being omitted; 101 ids → `TOO_MANY_PROFILE_IDS`
- internal friendship: mutual true, one-way false, self false, unknown id false
- comment filters: defaults are spam and offence filtered and nothing else; an explicit `null` on
  any of them is refused; keywords are sanitized before they are counted; 51 keywords, a keyword of
  31 code points, a blank one, or one with nothing to match are refused; duplicates collapse by
  match key with the first spelling kept; `[]` clears; the internal policy carries all three

video-service:
- `/policy` relays the filters from user-service through the cache; a user-service without them
  relays null rather than a made-up default; a failure caches nothing
- the cache reads back what it wrote, one owner or a page at once; an entry that does not parse is a
  miss

interaction-service:
- owner comments on their own `NO_ONE` video → allowed
- `commentsDisabled` beats a permissive audience; `NO_ONE` closes a video whose switch is untouched
- `FRIENDS`: mutual allowed, one-way refused, with `COMMENTS_NOT_ALLOWED` not `COMMENTS_DISABLED`
- video-service unreachable → comment goes through (fail-open, as today)
- `filterAllComments` holds everyone's comment but the owner's; a keyword holds the comments that
  contain it; with `filterSpamOffensive` off a link is posted and keeps its flag; a clean comment
  stores no reason

chat-service:
- `EVERYONE` opens; `NO_ONE` refuses; `FRIENDS` mutual opens and one-way refuses
- a conversation opened before the setting tightened still accepts messages, and reopens through
  `POST /conversations` without the audience being consulted
- user-service unreachable → `DM_SETTINGS_UNAVAILABLE`, not a silent open

api-gateway:
- `/api/v1/users/internal/settings` and `/internal/friendship` are refused from outside — `403`
  with a valid token, `401` without one (the entry point answers before the deny rule is reached),
  and in neither case does the request arrive at user-service
