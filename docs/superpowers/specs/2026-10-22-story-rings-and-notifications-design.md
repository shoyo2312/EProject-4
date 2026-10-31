# Story rings everywhere + story notifications — design

Date: 2026-10-22 · Status: draft, awaiting approval
Builds on `2026-10-20-stories-design.md` (stories end to end).

## 1. Why

Stories work, but nobody finds out about them. The only places that show a story exists are the
tray on the Following page and the ring on a profile header, and that ring is always grey on the
owner's own profile (`hasUnseen: !own && …` in `ProfileStoryAvatar`), so someone who just posted
never sees red. Nothing is announced either: story-service publishes no event once a story goes
live, and notification-service listens to no story topic. A test on 2026-10-22 confirmed it: a
story went ACTIVE in ~5s and no notification was written.

This makes a live story visible where people already look: a ring on the author's avatar wherever
it appears in the app, and one inbox entry for the people allowed to watch it.

## 2. Decisions

Decided by the user:

- **Red ring** around an avatar whose owner has a story the viewer may watch — on the profile and on
  the other pages that show user avatars.
- **Followers who may see a story are notified** — "A posted a new story".
- **Several stories in a row from one person collapse into one notification.**

Proposed in the conversation and not objected to (flag if wrong):

- Rings on other people's avatars across the app; clicking a ringed avatar opens the stories
  instead of the profile.
- The owner's own avatar is red while they have a live story.
- Recipients follow the story's visibility: PUBLIC → followers, FRIENDS → friends (mutual
  follows), PRIVATE → nobody. Never across a block; never for a banned or closed owner.
- Sent when the story goes live (ACTIVE), not when it is posted — a story can still be held or
  rejected in between.
- The collapse window is the existing 24h one (`NotificationServiceImpl.COLLAPSE_WINDOW`): the
  first story in 24h notifies, later ones in that window add no entry and no push. The ring still
  shows the newer story.
- Tapping the notification opens the owner's stories; if none are visible any more, the owner's
  profile.
- English wording, matching the rest of the inbox ("{username} posted a new story"); pushed to
  devices like every other notification.

Builder's calls (made in this draft, flagged so they can be checked):

- **Ring colours keep today's meaning**: red while the viewer has an unseen story of that owner,
  grey once all are seen. Own avatar: red if any of the owner's stories is ACTIVE; grey if they
  only have pending / held / rejected ones (still clickable, to see the status).
- **Where rings appear**: §5.4.
- **New topic `story.activation-events`** rather than a second event type on `story.story-events`
  (see §4, last point).
- **Widening a PRIVATE story announces it.** A story posted PRIVATE is announced if its owner
  switches it to PUBLIC/FRIENDS within its 24h. An announced story is never announced again,
  whatever its visibility changes to afterwards.
- **Stories that were live before this ships are never announced.**
- **The fan-out re-checks the story when it runs, not only when it is queued** (§6.5). The user
  asked for "those who can view it"; a snapshot taken at poll time stops being true whenever the
  fan-out runs late — user-service down (retried until it is back), a DLT replay, or
  notification-service reading the new topic for the first time after story-service has been
  publishing to it. Costs one internal story-service endpoint (§6.2).
- **Followers who muted the owner are not notified.** `docs/user-service-api.md` §3.11 defines
  mute as hiding an account's content "khỏi feed/thông báo" and leaves applying it to each service;
  this is the first notification that broadcasts someone's content to their followers. Rings
  ignore mutes, as the story tray does today.

## 3. Non-goals

- Music on stories — a separate design. Licensing notes from the conversation: only CC0 / CC BY
  tracks may go into an in-app library (CC ND forbids syncing to video, SA would bind the story,
  NC blocks commercial use); there is no free legal source of Vietnamese or international hits.
- A setting to mute story notifications; notifying non-followers; notifications for new videos.
- Mobile app (`tiktok_mobile`), admin console.
- Rings in navigation chrome (own avatar in the side nav / top bar), settings lists, the share
  sheet, the repost badge, follow-request lists, the comment composer, the story viewer itself.

## 4. Anti-goals — would make this a failure even if it works

- A ring or a notification that tells someone a story exists when they could not open it. Rings
  use the audience rules `GET /stories/users/{id}` uses (`StoryAudience`, plus ACTIVE / not
  expired / not deleted) — the same code, not a copy of it. Notifications go to a subset of that
  audience, counted from the follower side (§6.4), and only after the story is re-checked (§6.5).
- A notification for a story that is not ACTIVE.
- More than one STORY entry per (recipient, owner) per 24h — including after a redelivered event
  or a fan-out retried halfway through.
- One HTTP request per avatar from the browser.
- A burst of notifications on deploy for stories that were already live.
- `StoryActivatedEvent` on `story.story-events`: media-worker's `StoryModerationConsumer` has no
  `eventType` routing and would parse it as a `StoryCreatedEvent` with null fields, without an
  exception (the trap CLAUDE.md §Kafka describes).

## 5. Rings

### 5.1 API — `GET /api/v1/stories/rings?userIds=…`

Auth optional: a guest sees rings on PUBLIC stories of public accounts, as the profile ring does
today. The gateway's and story-service's `GET /api/v1/stories/*` permitAll already covers the
path; add an explicit matcher anyway, next to `/feed`, so the intent is visible.

Response: `[{userId, hasUnseen}]` for the ids that get a ring; an id with nothing to show is
absent.

- **Another owner**: present iff they have an ACTIVE, unexpired, undeleted story whose visibility
  `StoryAudience.resolve` allows for the requester. `hasUnseen` iff one of those stories has no
  `story_views` row for the requester (always true for a guest).
- **The requester's own id**: present iff they have any unexpired, undeleted story. `hasUnseen`
  iff one of them is ACTIVE.
- More than 100 ids → 400, the cap user-service's batch endpoints use. Duplicates are ignored.

Implementation: the queries `feed` already runs — one Mongo query for all ids (`author_status_idx`),
one `story_views` query, `StoryAudience.resolve` once over the owners that have ACTIVE stories — but
without signing media URLs. No new index.

Cost: `StoryAudience` still makes one or two user-service calls per owner with live stories, in
sequence (its existing `ponytail:`). A page whose authors mostly have stories pays that; the batch
relationship endpoint named there is the upgrade.

### 5.2 Web — one ringed-avatar component

Generalise `ProfileStoryAvatar` into one component used wherever §5.4 puts a ring: avatar + ring +
click-to-watch. It reads its ring state from a client-side batcher instead of making its own
request:

- Avatars on screen register their user ids; one `/stories/rings` call per tick covers the ids not
  already cached, in chunks of 100.
- Results are cached briefly so scrolling back does not refetch. When the viewer watches an
  owner's stories, that owner's entry turns grey locally (as `markStorySeen` does today). The
  viewer's own entry is refetched after they post or delete a story.
- Clicking a ringed avatar fetches `GET /stories/users/{id}` and opens `StoryViewer` at the first
  unseen story, as `ProfileStoryAvatar` does. An avatar without a ring keeps its current behaviour
  (usually a link to the profile).
- Only backend accounts (numeric ids) are asked about; the template's static profiles never have a
  ring (existing rule in `ProfileStoryAvatar`).
- The look is the existing `StoryRing` (`--tt-red` / grey), scaled to the avatar's size.

### 5.3 Profile header

Keeps its own `GET /stories/users/{id}` (it needs the stories to open the viewer anyway) and
follows §2's own-avatar rule.

### 5.4 Where rings appear

Ring: the profile header; the feed's action rail (author avatar on For You, Following and the video
page); comment items; user results in search; the following list under the side nav and its
drawer; the follower/following list modal; Activity rows (the actor's avatar); suggested creators;
the explore grid.

No ring: the places listed in §3. For anything not named: another person's avatar shown as content
gets a ring; navigation, settings and action UI do not.

## 6. Notifications

### 6.1 Flow

```
media.story-moderation-events → story-service decide(): PENDING_MODERATION → ACTIVE, sets activatedAt
story-service poll (5s)       → story.activation-events (StoryActivatedEvent, key = owner id)
notification-service          → story-service GET /internal/{storyId}/announcement (still live? current visibility)
                              → user-service GET /internal/story-audience (paged)
                              → one STORY notification per recipient, collapsed per owner for 24h
```

### 6.2 story-service

- `Story` gains `activatedAt` (set by `decide()` when the target status is ACTIVE) and
  `announcedAt` (the outbox flag for this event; `eventPublishedAt` stays the created event's).
- A second poll, shaped like `StoryEventPublisher`'s (every 5s, batch of 100, oldest `activatedAt`
  first, through `OutboxDispatcher` so a story is marked only after the broker acks), over stories
  with `status = ACTIVE`, `activatedAt` set, `announcedAt` null, not deleted, not expired, and
  `visibility` PUBLIC or FRIENDS.
  - Requiring `activatedAt` is what keeps stories that were live before the deploy out: they have
    none.
  - PRIVATE stories are left unmarked, so widening one later within its 24h announces it then.
  - No ban check here: the fan-out's re-check (§6.5) drops stories of hidden owners.
- Topic `story.activation-events`, key = owner id, so one owner's announcements are consumed in
  order and the collapse check (§6.5) is never raced by that owner's next story on another
  partition. Topics are auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE`).
- An `OutboxBacklog` bean for this slot, with the poll's filter (CLAUDE.md §Kafka).

- Internal endpoint for the re-check:
  `GET /api/v1/stories/internal/{storyId}/announcement` → `{userId, visibility}` when the story is
  ACTIVE, unexpired, undeleted and its owner is not hidden (`OwnerBanRepository`); 404 otherwise.
  Guarded like every internal endpoint (CLAUDE.md §Endpoint nội bộ): `.access(new
  InternalCallerAuthorization(internalApiToken))` in story-service's `SecurityConfig`, matched
  before the `/api/v1/stories/*` permitAll; and `/api/v1/stories/internal/**` added to the
  gateway's `denyAll` list.

### 6.3 event-schema

`StoryActivatedEvent(eventId, occurredAt, storyId, userId)` in `com.tiktok.event.story`. No
visibility on it: the fan-out reads the current one (§6.5), and a second copy invites using the
stale one. Pinned by `EventJsonCompatibilityTest` like every other event.

### 6.4 user-service

`GET /api/v1/users/internal/story-audience?userId=&scope=FOLLOWERS|FRIENDS&after=&size=`
→ `{userIds: [...], next}` (`next` = cursor for the following page, null at the end).

- FOLLOWERS: accounts following `userId`. `user_follows` holds accepted follows only (pending
  requests live in `user_follow_requests`), so a private account's audience is its approved
  followers. FRIENDS: the subset `userId` follows back.
- Excludes blocks in either direction and deactivated accounts — the filters of
  `UserFollowRepository.findFollowerIdsVisibleTo`, with the owner as the viewer — and followers
  with a live `user_mutes` row muting the owner (`muter_id` = follower, `muted_id` = owner).
- Keyset paging on follower id (`after` = last id returned, `size` ≤ 500), so follows changing
  during a fan-out cannot shift the pages.
- Already under `/api/v1/users/internal/**`: internal-token guard in user-service, `denyAll` at the
  gateway. Nothing to add there.

### 6.5 notification-service

- `NotificationType.STORY`, included in `collapsesRepeats()`.
- A consumer of `story.activation-events` (group `notification-service`, whose
  `auto-offset-reset: earliest` already covers a new topic), inside
  `IdempotentEventProcessor.runOnce`:
  - First the re-check: story-service's internal `announcement` endpoint. 404 → nothing (deleted,
    expired, never went live, or owner hidden — this is also what keeps a late first read of the
    topic from announcing stories that are already gone). New client on the existing pooled
    internal-token pattern, `downstream.story-service-uri` (env `STORY_SERVICE_URI`, default
    `http://localhost:8086`; add it to the compose files). Unreachable story-service throws, so
    nothing is sent until the story is confirmed.
  - Scope from the visibility the re-check returned: PUBLIC → FOLLOWERS; FRIENDS → FRIENDS;
    PRIVATE → nothing.
  - Page through the audience; for each recipient
    `create(recipient, owner, STORY, "New story", "Someone posted a new story.", String.valueOf(owner))`.
    `referenceId` = owner id is both the collapse key — one entry per (recipient, owner) per 24h —
    and where a tap goes.
  - No per-recipient block call: the audience endpoint already excludes blocks.
  - A user-service failure throws, the claim is released, and kafka-lib retries (forever if
    user-service is unreachable, three times then `story.activation-events.DLT` for other errors —
    `make dlt-replay` re-runs it). Entries written before the failure collapse on the retry, so a
    partial fan-out never doubles.
  - `ponytail:` one lookup + insert + `notification.created` + push per recipient, in sequence.
    Fine for thousands of followers; a bulk insert is the upgrade.
  - Accepted gap: a change made while one fan-out is already paging (delete, narrowing, ban) does
    not stop the pages after it. The window is one fan-out's length.

### 6.6 Web

- `NotificationType` gains `"STORY"`; `referencesUser` includes it (its `referenceId` is a user id).
- `describe()` → `"posted a new story"`. Shown under "All activity" only (no new filter chip).
- Tap → the owner's stories in `StoryViewer` if `GET /stories/users/{id}` returns any, else the
  owner's profile. The row's avatar carries the ring (§5.4).

## 7. Tests (one runnable check per non-trivial piece)

- story-service: `/rings` — own red/grey, another owner unseen/seen, owner hidden / blocked /
  story not in the requester's audience → absent, guest, more than 100 ids → 400. `decide()` sets
  `activatedAt` only for ACTIVE. Activation poll — skips stories without `activatedAt`, PRIVATE,
  deleted, expired. `announcement` — 404 for deleted / expired / not ACTIVE / hidden owner, and
  403 without the internal token.
- user-service: story-audience query — followers vs friends, blocks in both directions,
  deactivated accounts, muted owner, keyset paging.
- notification-service: consumer — PUBLIC / FRIENDS recipients from the re-checked visibility,
  nothing on 404 or PRIVATE; a redelivered event and a second story within 24h add nothing.
- event-schema: compatibility pin.
- web: unit test for the ring batcher/cache logic (in `src/lib/stories/`); `npm run check`.

## 8. Docs

`docs/story-service-api.md` (`/rings`, the internal `announcement` endpoint, the activation event
and its topic), `docs/user-service-api.md` (internal story-audience; §3.11 now has a consumer), and
the root `CLAUDE.md`: the story bullet (new topic, announcement rule) and the internal-endpoint
list (`/api/v1/stories/internal/**`).

## 9. Left to the builder

Component, hook, class and DTO names; the batcher's tick and cache lifetime; whether `/rings` and
`feed` share code by extraction; ring thickness per avatar size; whether the activation poll lives
in `StoryEventPublisher` or its own class.

Working tree note: on 2026-10-22 `tiktok-cloned` had uncommitted story and following-sidebar work
(`FollowingAccounts`, `FollowingDrawer`, `use-following-accounts`, edits to the story components).
Build on it; do not revert it.
