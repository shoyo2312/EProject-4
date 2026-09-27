# Private account — design

Date: 2026-09-26 · Branch: `feat/private-account` · Status: approved

## 1. Problem

The web client's `/setting` page has a "Private account" switch that does nothing. It is a
`SessionSwitchRow`: it holds its position until a reload and never calls the API, because there is
nothing to call — user-service has no such field, and `docs/user-settings.md` §0 deferred the
feature as "own document, own sprint". This is that document.

The row's own copy is the specification:

> With a private account, only users you approve can follow you and watch your videos. Your
> existing followers won't be affected.

## 2. Decisions

Agreed on 2026-09-26:

- **What is hidden from someone who does not follow the account:** its videos, its
  followers/following lists, and its Reposts tab.
- **Follow requests notify both ways:** the owner is told someone asked, the requester is told they
  were accepted. A rejection is silent.
- **Switching back to public approves every pending request.** Nothing gates following any more,
  and these people already asked.
- **Followers see a private account's videos the way friends see FRIENDS videos today:** on the
  profile grid, the video page and the Following feed. Never on the global surfaces — For You,
  trending, the newest feed, search — for anyone, follower or not. That keeps every global surface a
  set of videos anyone may see, which is the rule all of them already implement.
- Existing followers keep access. A block still wins over everything. The owner always sees
  everything of their own. Profile counters (followers, following, likes, video count) stay visible,
  as on TikTok.

Out of scope: removing an existing follower; stories; direct messages (the separate "Direct
messages" setting governs those); the frontend, which follows once this is merged and consumes the
API in §4–§6.

## 3. The rule

A **follower** of O is anyone with an undeleted `user_follows` edge to O. A follow request is not a
follow: until it is approved there is no edge, so every existing check that reads edges keeps
meaning what it meant.

For a PUBLISHED video owned by O, viewer R may see it when:

| Video visibility | O public | O private |
|---|---|---|
| PUBLIC | everyone | R == O, or R follows O |
| FRIENDS | R == O, or R and O follow each other | same — mutual follow implies R follows O |
| PRIVATE | R == O | R == O |

Plus everything that already applies: status must be PUBLISHED unless R is the owner, and a block
between R and O hides the video from both.

**Effective audience.** Services that keep their own copy of a video need one word for "who may see
this". It is the video's visibility, except that a PUBLIC video of a private owner is `FOLLOWERS`:

```
audience(video) = video.visibility == PUBLIC && video.ownerPrivate ? "FOLLOWERS" : video.visibility
```

Every consumer that keeps a copy already treats anything other than `PUBLIC` as hidden, so
`FOLLOWERS` needs no code in search-service, recommendation-service or chat-service (§8).

## 4. user-service

### 4.1 Storage

**`V11__add_private_account_to_user_profiles.sql`** — `private_account BOOLEAN NOT NULL DEFAULT
FALSE` on `user_profiles`.

On the profile rather than on `user_settings`, deliberately. V10 put settings in their own table
because only the owner reads them and `GET /users?ids=` batches profiles on hot paths. This one is
the opposite case: every viewer needs it — the profile header, the Follow button — and it rides the
profile reads that already happen. It is still *written* through the settings API (§4.2), because
that is where the switch lives.

**`V12__create_user_follow_requests_table.sql`**:

```sql
CREATE TABLE user_follow_requests (
    id            BIGINT       PRIMARY KEY,
    requester_id  BIGINT       NOT NULL,
    target_id     BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    deleted_at    TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_follow_requests_requester_target
    ON user_follow_requests (requester_id, target_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_follow_requests_target_created
    ON user_follow_requests (target_id, created_at DESC) WHERE deleted_at IS NULL;
```

Same shape as `user_follows` (V2): Snowflake id, audit columns, soft delete. Approving, rejecting,
cancelling and blocking all end a request by setting `deleted_at`; the unique index only covers live
rows, so asking again after a rejection works. Entity `FollowRequest extends BaseEntity`.

### 4.2 API

**Settings** (`/users/me/settings`):

- `GET` answers `privateAccount`, read off the caller's profile; `false` when the profile row does
  not exist yet.
- `PATCH` accepts `privateAccount`, three-valued like the other keys: absent leaves it alone, `null`
  is `400 VALIDATION_ERROR` (the column is NOT NULL). A value equal to the current one leaves the
  column alone and queues nothing, whatever else the same PATCH changes. A change:
  - sets the column and queues `UserPrivacyChangedEvent` in the same transaction;
  - when going **public**, approves every pending request in that same transaction (§4.4).
  - With no profile row: `404 USER_PROFILE_NOT_FOUND`.

**Profiles.** `UserProfileResponse` gains `privateAccount` — on `/me`, `/{id}`, `/by-username`, the
`?ids=` batch, search and every list built from profiles.

**Follow** (`/users/{id}/follow`):

- `POST` keeps its checks in their order (self, both profiles exist, block, already following), then:
  if the target is private, it creates a request instead of an edge. `FollowResponse` gains
  `status`: `FOLLOWING` or `REQUESTED`; both answer `201`. A live request already there is
  `409 ALREADY_REQUESTED`, backed by the unique index exactly as `ALREADY_FOLLOWING` is backed by
  `uq_user_follows_follower_following` (`saveAndFlush`, violation mapped to the 409).
- `DELETE` removes the edge as today; with no edge it cancels the caller's pending request; with
  neither it is `404 NOT_FOLLOWING`.

**Relationship** — `GET /users/{id}/friendship` and the internal `GET /users/internal/friendship`
share `FriendshipResponse`, which gains:

| Field | Meaning |
|---|---|
| `following` | the viewer follows this user |
| `requested` | the viewer has a pending request to this user |
| `privateAccount` | this user's account is private |

`friends` keeps its meaning. On the internal endpoint `userA` (the viewer) becomes optional: absent
means an anonymous viewer, for whom only `privateAccount` can be true. One call now answers
everything a Follow button needs — Follow, Following or Requested — so the client no longer has to
walk its own following list to find out.

**Follow requests** — the owner's side, new `FollowRequestController`:

| Endpoint | Answer | Errors |
|---|---|---|
| `GET /users/me/follow-requests` | `Page<UserProfileResponse>`, newest first | — |
| `POST /users/me/follow-requests/{requesterId}/approve` | `FollowResponse`, `status: FOLLOWING` | `404 FOLLOW_REQUEST_NOT_FOUND` |
| `DELETE /users/me/follow-requests/{requesterId}` | empty | `404 FOLLOW_REQUEST_NOT_FOUND` |

Approving creates the edge through the same path as a direct follow — counters, `UserFollowChangedEvent`
— with `requestApproved = true` on the event. The list resolves ids to profiles with
`UserProfileBatchAssembler` like every other list; no block filter is needed, because a block
deletes the requests between the two accounts (below).

**Followers / following lists** of a private account: after the existing block check (still a
`404`), a viewer who is neither the owner nor a follower gets `403 PRIVATE_ACCOUNT`. A 403 is fine
here where a block uses a 404: that the account is private is already on its profile, so the status
confirms nothing.

**Internal batch follow check** — `GET /users/internal/following?followerId=&ids=` answers the subset
of `ids` that `followerId` follows, capped at 100 like the other batch endpoints
(`400 TOO_MANY_PROFILE_IDS`). video-service asks it once per request for all the private owners on a
page (§5.2). Permitted in `SecurityConfig` under the existing `/api/v1/users/internal/**` rule and
denied at the gateway like the rest of that prefix.

**Blocking** soft-deletes pending requests in both directions, in the same transaction that already
severs the follow edges.

### 4.3 Events

The outbox publisher routes rows by aggregate type, one event type per topic, so no consumer has to
route on an `eventType` header (and `user.follow-events` keeps carrying one type, as its consumer
assumes):

| Aggregate type | Topic | Event | Key |
|---|---|---|---|
| `USER_FOLLOW` | `user.follow-events` | `UserFollowChangedEvent` (+ `requestApproved`) | `followingId` |
| `FOLLOW_REQUEST` | `user.follow-request-events` | `FollowRequestedEvent` | `targetId` |
| `USER_PRIVACY` | `user.privacy-events` | `UserPrivacyChangedEvent` | `userId` |

New, in `libs/event-schema` under `com.tiktok.event.user`:

- `UserPrivacyChangedEvent(eventId, occurredAt, userId, privateAccount)`
- `FollowRequestedEvent(eventId, occurredAt, requesterId, targetId)`

`UserFollowChangedEvent` gains `boolean requestApproved`; payloads written before it read as `false`.
Event ids are fixed when the outbox row is written, so a re-send carries the same id. Topics are
auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE`).

### 4.4 Going public

Every pending request of the owner is approved in the transaction that flips the flag: an edge per
requester, counters, one `UserFollowChangedEvent` each with `requestApproved = true` (so each
requester is told), and the requests soft-deleted.

`ponytail:` one transaction for all of them. Fine at this scale; an account with thousands of
pending requests would want the approvals batched by a job instead.

## 5. video-service

### 5.1 Knowing who is private

**`owner_privacy` collection** — `{ _id: userId, privateAccount, changedAt }`, written only by the
consumer below. It is video-service's own answer to "is this owner private" for the paths that need
it without a video in hand: publishing, the profile grid, the Following feed. An owner with no
document is public, which is right for every account that existed before this shipped — none of
them could be private.

**`Video.ownerPrivate`** — absent on existing documents, which Mongo maps to `false`. Also on
`VideoResponse`, so the visibility check can decide from a cached entry like it does for
`visibility` and `status`, and clients can show a lock.

**`UserPrivacyEventConsumer`** on `user.privacy-events`, group `video-service`, claimed through
`IdempotentEventProcessor` like the other consumers:

1. Upsert `owner_privacy` only where `changedAt <= event.occurredAt`. An older event replayed from
   the DLT after a newer one changes nothing, and stops here. `<=` rather than `<`: a redelivery of
   the same event, whose first attempt failed after this step, must still reach steps 2 and 3.
2. `updateMulti` the owner's undeleted videos: `ownerPrivate = event.privateAccount`,
   `visibilityEventPendingAt = now` — the per-video visibility outbox (§5.3) re-announces each one.
3. Evict each of those videos from `VideoCache`. Same ceiling as every other eviction: a reader
   already on its way back from Mongo can re-cache the old entry for one TTL (60s).

**Publishing** stamps `ownerPrivate` from `owner_privacy`, then — after the insert — reads
`owner_privacy` again. If the value changed in between, it writes the new one and queues a
visibility event. Both reads are needed. With `C` the consumer (step 1 then step 2) and `P` the
publish (read, insert, re-read):

- if step 2 runs after the insert, it rewrites the new video along with the rest;
- if step 2 runs before the insert, step 1 ran before it too, so the re-read after the insert sees
  the new value.

Either way the video ends up with the owner's current setting; with only the first read, a toggle
landing between that read and the insert would leave one video wrong for good.

### 5.2 Read paths

**`isVisibleTo`** follows the table in §3. Follow checks go through a new `FollowClient` calling
`/users/internal/following` — one call per request, covering every private owner on it that the
viewer does not own. Fails closed like `FriendshipClient`: a lookup failure hides the videos of
private owners, logged as a warning. Public owners never trigger a call, so a normal feed page
makes none.

| Path | Change |
|---|---|
| `GET /videos/{id}` | `isVisibleTo` with one follow check when needed. This is also the gate interaction-service asks before a like, comment, save, share or view, and the endpoint chat-service asks as an anonymous viewer before sending comment frames — both follow from it unchanged. |
| `GET /videos/batch` | `isVisibleTo`, one follow call for the whole batch. For You and trending are hydrated here. |
| `GET /videos/users/{userId}` (profile grid) | Owner private per `owner_privacy`, viewer neither owner nor follower: `403 PRIVATE_ACCOUNT`. Followers get the grid as today (PUBLIC, plus FRIENDS for friends). |
| `GET /videos/feed` (newest) | The query adds `ownerPrivate != true`. |
| `GET /videos/feed/following` | Gains the optional caller. Private owners among the ids (`owner_privacy $in`) whose videos the caller may not see are dropped before the query: the ids come from the client, and nothing else stops a non-follower from naming a private account. Anonymous callers lose every private owner. |
| `GET /videos/users/{userId}/stats` | Unchanged — counters stay public. |

### 5.3 Events

`VideoPublishedEvent.visibility` and `VideoVisibilityChangedEvent.visibility` carry the effective
audience (§3) instead of the stored visibility. The stored value, and `VideoResponse.visibility`,
are untouched: the owner's controls still show PUBLIC / FRIENDS / PRIVATE.

The visibility poll only picks up videos whose publication has already been acknowledged
(`eventPublishedAt != null`). Owner visibility changes could already queue an event before the
publication went out; the consumer above queues one for every video of an owner at once, which makes
that routine. Since the two polls run on separate threads, without this a visibility event could
reach the partition ahead of its video's publication. A video still waiting on its publication is
announced on a later poll, after it; its publication is built from the document at send time and
already carries the current audience.

**Staleness.** video-service's own read paths switch as soon as the consumer runs — user-service's
outbox polls every 5s. Search, For You and trending follow the visibility poll at 100 videos per
5s, so an account with 1,000 videos leaves them within about 50s. `ponytail:` fine at this scale;
raise the batch size if accounts with tens of thousands of videos appear.

## 6. interaction-service

`GET /interactions/users/{userId}/reposts` takes the optional caller. The owner being private, and
the caller neither the owner nor a follower, is `403 PRIVATE_ACCOUNT`. Asked of
`/users/internal/friendship` (`userA` = the caller, absent when anonymous). Fails closed: a failed
lookup is treated as private and not followed.

Unchanged: the repost badge (`repost-context/batch`), because the client already shows only
reposters it follows; and the interaction gate, which asks video-service as the caller.

## 7. notification-service

`NotificationType` gains `FOLLOW_REQUEST` and `FOLLOW_REQUEST_ACCEPTED`, both collapsing repeats
like `NEW_FOLLOWER` — asking, cancelling and asking again is one entry.

- **New `FollowRequestEventConsumer`** on `user.follow-request-events`: `FOLLOW_REQUEST` to
  `targetId`, actor and reference `requesterId` (the deep link opens the requester's profile).
- **`FollowEventConsumer`**: when `requestApproved`, `FOLLOW_REQUEST_ACCEPTED` to `followerId`,
  actor and reference `followingId` — and no `NEW_FOLLOWER` to the owner, who just approved it
  themselves.

`ponytail:` a request that is cancelled or rejected leaves its notification in the owner's inbox.
The requests list is the authority; retracting notifications would need a delete path
notification-service does not have.

## 8. Unchanged

- **search-service** — its index filters `visibility == PUBLIC`; `FOLLOWERS` falls outside it.
- **recommendation-service** — any visibility other than PUBLIC puts the video in its private set
  and withdraws it from the tag indexes and trending; PUBLIC again reindexes it.
- **chat-service** — asks video-service as an anonymous viewer, which now answers 404 for these
  videos, so their comment frames are held back. The visibility events above also make it forget
  its cached answer.
- **api-gateway** — the new paths sit under `/api/v1/users/**` and `/api/v1/interactions/**`, which
  are already routed; `/internal/` is already denied.

## 9. Errors

| Code | HTTP | Raised by |
|---|---|---|
| `PRIVATE_ACCOUNT` | 403 | user-service lists, video-service profile grid, interaction-service reposts |
| `ALREADY_REQUESTED` | 409 | `POST /users/{id}/follow` |
| `FOLLOW_REQUEST_NOT_FOUND` | 404 | approve / reject |

## 10. Testing

In each service's existing style — Mockito service tests, `MockRestServiceServer` for clients,
consumer tests with mocked collaborators:

- **user-service** — follow on a private account creates a request; `409 ALREADY_REQUESTED`;
  `DELETE` cancels a request; approve and reject, including `404`; going public approves everything
  pending and going private does not touch existing edges; the privacy toggle queues exactly one
  event and none when unchanged; lists answer `403` to strangers and pass followers; block deletes
  requests both ways; outbox routing by aggregate type; the internal batch follow check and its cap.
- **video-service** — the §3 table for owner, follower, stranger and anonymous viewer, FRIENDS on a
  private owner included; the newest feed excludes private owners; the Following feed drops private
  owners the caller does not follow; the profile grid `403`; the consumer's upsert guard, fan-out
  and eviction; the double read on publish; the audience written into both events; the visibility
  poll skipping unannounced videos; `FollowClient` failing closed.
- **interaction-service** — reposts for owner, follower, stranger, anonymous, and a failed lookup.
- **notification-service** — both consumers, including no `NEW_FOLLOWER` for an approval.

Every touched service's full suite passes before its commit.

## 11. Rollout

- Both migrations are additive, and their defaults leave every account public. No backfill: no
  account can be private before this ships.
- Every reader tolerates the old shape: a missing `ownerPrivate` reads `false`, a missing
  `owner_privacy` document means public, a missing `requestApproved` reads `false`.
- Order: `event-schema`, then the four services in any order — until user-service ships, nothing
  can become private, so the enforcement sits idle rather than half-applied.

## 12. Documents to update with the code

`user-service-api.md` (endpoints, errors, and the §8 event list), `video-service-api.md` (the same),
`interaction-service-api.md`, and `user-settings.md` §0 — which should point here instead of listing
the feature as deferred. notification-service has no API document; its two new types are listed in
the enum's own javadoc.
