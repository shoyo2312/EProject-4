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
- **Notification preferences** — belongs in notification-service (Mongo, `_id = userId`), gated in
  `NotificationServiceImpl.create` where `BlockClient` is already consulted.
- **Keyword filters, data export, business verification.**

Those are listed so nobody reads this document as the whole of `/setting`.

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
  "weeklyScreenTimeDigest": true
}
```

A **flat keyed object**, not the section/group tree the page draws. Layout is presentation and
stays in the client; this is the values. Getting that boundary wrong is what forces bespoke wiring
per row.

`null` on the three screen-time fields means "off" — there is no separate enable flag, and a
`screenTimeLimitMinutes` of `0` is a validation error rather than a second way to say off.

`sleepHoursStart`/`sleepHoursEnd` are **wall-clock times with no timezone**, stored `TIME` and
carried as `LocalTime`, on the wire as `"HH:mm"` in both directions (`@JsonFormat`, minute
precision) so a client can PATCH back exactly the string it read — the asymmetry that
`avatarUrl` on `PATCH /me` had to be widened for. A time that does not parse is the body parser's
`BAD_REQUEST`, not `VALIDATION_ERROR`; the enums are `@Pattern`-checked instead precisely so an
unknown audience names all three values. No account on this platform has a timezone attached — nothing in
auth-service or user-service stores one — so these can only mean "22:30 wherever the viewer is",
interpreted by whichever client enforces them. That is the right semantics for the feature (a
sleep reminder should follow you across timezones, not chase the one you signed up in), but it
does mean the server can never schedule anything off these fields. If a server-side reminder is
ever wanted, it needs an account timezone first — a separate decision, not a column to slip in
here.

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
on one of the three NOT NULL fields is a `VALIDATION_ERROR` rather than a silent no-op. Unlike `PATCH /users/me` there is no
empty-string-clears trap here — every field is a boolean, an enum or a nullable scalar, and an
explicit `null` on a nullable field *is* the way to clear it. That difference is worth a line in
the client doc, because the two endpoints sit next to each other and behave differently on purpose.

Validation:

- `commentAudience`, `dmAudience` ∈ `EVERYONE | FRIENDS | NO_ONE` → `VALIDATION_ERROR` (400)
- `screenTimeLimitMinutes` ∈ `[10, 1440]` or null
- `sleepHoursStart`/`sleepHoursEnd` — both null or both set; equal start and end is a
  validation error, but start > end is **not** (a window that crosses midnight is the normal case)

Optimistic locking is inherited from `BaseEntity`, so two devices writing at once produce the
existing `CONCURRENT_MODIFICATION` (409) — the one 409 the client is told to retry.

Two devices writing for the *first* time is the same answer by a different mechanism: both read no
row, both insert, and `uq_user_settings_user_id` stops the second. That is the check-then-act race
`FollowServiceImpl.follow` already handles, so the insert is a `saveAndFlush` and the violation is
translated to the same 409 rather than escaping as a 500 — the caller's move is identical either
way, and the retry finds the row and updates it.

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
{ "userId": 123, "commentAudience": "EVERYONE", "dmAudience": "EVERYONE" }
```

Only the two enforcement-relevant fields. Nobody outside user-service needs to know what somebody's
screen-time preferences are, and an internal endpoint that hands them over anyway is a privacy leak
waiting for its first careless caller.

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

## 4. Enforcement

Storing the values is the easy half. These are the two that actually change behaviour.

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

### 4.3 Stored, not enforced

The four screen-time fields are **persisted and returned, and nothing reads them**. That is the
honest state, not an oversight: screen-time limits are client-enforced on the real product too —
the server's job is to sync the preference across devices, not to police it. See §2.1 on why it
could not police them anyway without an account timezone.

Persisting them is still worth doing, because the alternative is a control that forgets what the
viewer told it. The weekly digest is the one that later grows a consumer: a scheduled job in
notification-service emitting `NotificationType.SYSTEM`, which already exists, over ClickHouse
`watch_events` (`user_id`, `watched_ms`, `occurred_at` are all already there). Out of scope here.

### 4.4 Explicitly not server-side: colour contrast

`Accessibility → Increase color contrast` stays in the web client's `localStorage`. The row's own
copy scopes it to "your computer", it is already shipped that way, and moving it server-side buys
cross-device sync at the cost of a write per toggle plus a reconciliation story between the stored
value and the local one. Revisit only if someone asks for it by name.

## 5. Events

**None.** Tier 2 needs no new Kafka event and no `libs/event-schema` addition.

The only consumer of a settings change outside user-service is video-service's policy cache, and a
short TTL handles that — a comment audience that takes up to a minute to tighten is not a
correctness problem, and the outbox row plus a consumer plus the ordering question is a lot of
machinery to avoid one minute of staleness.

The TTL was later cut from 30s to 3s: once every `VideoResponse` carried `accountCommentsOff`, a
stale entry became something the owner sees right after changing the setting, and half a minute of
it read as a bug. An event would not have been faster — user-service's outbox polls every 5s.

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

`VALIDATION_ERROR`, `CONCURRENT_MODIFICATION` and `TOO_MANY_PROFILE_IDS` are reused as-is. All three
new codes need client copy in the web client's `lib/api/errors.ts` `MESSAGES` map, which is where
user-facing wording lives — the contracts are explicit that the server's own `message` is not shown.

## 7. Rate limiting

The gateway's 20 req/s is keyed on **IP**, and the web client reaches it through a Next.js rewrite,
so every browser viewer shares the Next server's address — `next.config.ts` already warns about
this. A settings PATCH is cheap enough not to care. Nothing here needs a per-account limit; the
endpoints that will are `data-export`, `deactivate`/`delete` and `business-verification`, none of
which are in this tier.

## 8. Test checklist

user-service:
- read with no row → defaults, `200`, and no row created as a side effect
- write with no row → row inserted with patch-over-defaults
- absent key leaves the field; explicit `null` clears a nullable one
- each enum rejects an unknown value; `screenTimeLimitMinutes` boundaries at 9/10/1440/1441
- sleep hours: both-or-neither, start == end rejected, start > end accepted
- internal settings: unknown id returns defaults rather than being omitted; 101 ids → `TOO_MANY_PROFILE_IDS`
- internal friendship: mutual true, one-way false, self false, unknown id false

interaction-service:
- owner comments on their own `NO_ONE` video → allowed
- `commentsDisabled` beats a permissive audience; `NO_ONE` closes a video whose switch is untouched
- `FRIENDS`: mutual allowed, one-way refused, with `COMMENTS_NOT_ALLOWED` not `COMMENTS_DISABLED`
- video-service unreachable → comment goes through (fail-open, as today)

chat-service:
- `EVERYONE` opens; `NO_ONE` refuses; `FRIENDS` mutual opens and one-way refuses
- a conversation opened before the setting tightened still accepts messages, and reopens through
  `POST /conversations` without the audience being consulted
- user-service unreachable → `DM_SETTINGS_UNAVAILABLE`, not a silent open

api-gateway:
- `/api/v1/users/internal/settings` and `/internal/friendship` are refused from outside — `403`
  with a valid token, `401` without one (the entry point answers before the deny rule is reached),
  and in neither case does the request arrive at user-service
