# Stories, end to end — design

Date: 2026-10-20 · Status: draft, awaiting approval

## 1. Why

story-service exists but nobody can use it: the gateway has no route to it, prod compose leaves it
out (its Mongo URI is a hard-coded localhost one), no frontend has a line of story code, and a
story's media is a string the client makes up. It also breaks two of the repo's own rules: nothing
is moderated, and banned or closed accounts' stories still show.

This makes stories a real feature on the web app (`tiktok-cloned`): post a photo or short video,
choose who sees it, have it screened before anyone else does, watch the stories of people you
follow, see who watched yours.

## 2. Decisions

Decided by the user:

- Scope is (1) usable end to end and (2) moderation. Frontend is the web app `tiktok-cloned` only.
- **Visibility per story: PUBLIC / FRIENDS / PRIVATE**, the same three modes and the same meaning
  as a video's (`VideoVisibility`): PUBLIC = anyone (followers only if the owner's account is
  private), FRIENDS = mutual followers, PRIVATE = owner only.
- **A story the model is unsure about (REVIEW) is hidden and simply expires.** No admin queue, no
  `tiktok-admin` work.

Builder's calls (made in this draft, flagged so they can be checked):

- **24h counts from approval**, not from posting, so screening time does not eat display time.
- **Visibility can be changed after posting** (like `PATCH /videos/{id}/visibility`).
- **Video stories play the raw upload** — no transcode. Accepted types are the ones browsers play
  natively: `video/mp4`, `video/webm`, `image/jpeg`, `image/png`, `image/webp`. Limits: video ≤ 60s
  and ≤ 50 MB, image ≤ 10 MB.
- **Rejected stories are not announced.** The owner sees the "rejected" label on their own story
  in the tray; no notification is sent.
- Entry point to post is a "+" bubble on the viewer's own avatar at the start of the story tray.

## 3. Non-goals

- Mobile app (`tiktok_mobile`), admin console, admin review of held stories.
- Likes/replies on stories, highlights (stories kept beyond 24h), stickers/music/filters, editing
  a story's media or caption after posting.
- Transcoding story videos, HLS.
- Notifications of any kind (new story, rejection).

## 4. Anti-goals — would make this a failure even if it works

- Any story media reachable by anyone before it is APPROVED, or after it is rejected/held/deleted
  for longer than one signed URL's life (§5.2).
- A moderation failure that ends up as "shown". Failure is REVIEW, which here means hidden.
- A FRIENDS/PRIVATE story, or a private account's story, visible to someone the equivalent video
  would not be visible to. When in doubt, follow `VideoServiceImpl.isVisibleTo` exactly.
- Stories of banned or closed accounts showing.

## 5. Backend

### 5.1 Lifecycle

```
POST upload-url → browser POSTs file to MinIO (stories/{userId}/{storyId}.{ext})
POST /stories   → caption checked (moderation-lib); story saved PENDING_MODERATION
                → outbox poll → story.story-events (StoryCreatedEvent)
media-worker    → downloads object, probes/samples, asks moderation-service, folds caption verdict
                → media.story-moderation-events (StoryModerationCompletedEvent)
story-service   → PENDING_MODERATION → ACTIVE | HELD | REJECTED
```

`StoryStatus`: `PENDING_MODERATION`, `ACTIVE` (approved), `HELD` (REVIEW verdict), `REJECTED`.
Every viewer-facing read filters `status == ACTIVE` (whitelist, as videos do). The owner sees all
of their own unexpired stories with the status.

`expiresAt` (still the TTL index):
- on create: `createdAt + 48h` — a ceiling, so a story whose verdict never arrives is removed
  rather than kept forever;
- on ACTIVE: `now + story.ttl-hours` (24h);
- on HELD/REJECTED: unchanged.

`StoryView.expiresAt` is copied from the story at view time, as today.

### 5.2 Media

- `POST /api/v1/stories/upload-url {contentType}` → presigned POST, same shape and mechanics as
  `VideoServiceImpl.createUploadUrl` (key and content type pinned, `content-length-range` signed
  with the per-type byte limit). Key: `stories/{userId}/{snowflake}.{ext}`. Response returns the
  `s3://` location, which the create call echoes back.
- `stories/` is **not** in the bucket's anonymous-read policy (`media-worker/BucketInitializer`)
  and must not be added. Viewers get a **presigned GET URL** (1h) in `StoryResponse.mediaUrl`,
  issued only for stories the requester may see at that moment. No quarantine moves are needed:
  hiding a story is just not issuing URLs any more. Ceiling: a URL already handed out keeps working
  until it expires (≤ 1h) after delete/visibility change — accepted. The owner always gets a URL
  for their own unexpired stories, whatever the status (it is their own upload).
- SigV4 signs the host, so the presigning client must use the address the browser reaches. Same as
  video-service: in prod `MINIO_ENDPOINT` is the public `MEDIA_PUBLIC_URL`
  (`docker-compose.prod.yml`), with `region` set so signing needs no network call.
- No per-account upload rate limit in this round (story-service has no Redis); the signed byte
  limit bounds each upload. Mark with `ponytail:`; the upgrade is video-service's
  `UploadRateLimiter`.
- Cleanup: a MinIO lifecycle rule on prefix `stories/` expiring objects after 3 days, set in
  `BucketInitializer` next to the policy (re-applied on startup the same way). The Mongo TTL
  removes the documents; nothing else deletes story objects.
- Create validates the key starts with `stories/{requesterId}/` (else 403, like
  `ForeignUploadException`), the object exists, and the extension maps to the declared media type.
  A unique index on the media key stops one upload backing two stories.

### 5.3 Visibility

Same rules as `VideoServiceImpl.isVisibleTo`, per story:

| | Viewer is owner | Owner banned/closed | PUBLIC | FRIENDS | PRIVATE |
|---|---|---|---|---|---|
| Visible | always (any status) | no | owner public, or viewer follows a private owner | mutual follow | no |

Plus `status == ACTIVE`, not deleted, not expired, and no block either way (as video single-read).

story-service keeps its own copies, exactly as video-service does — copy, don't invent:
- `owner_bans` (ban pair + closure pair with separate guards, guards matching null) from
  `admin.moderation-events` (route on `eventType` header) and `auth.account-events`;
- `owner_privacy` from `user.privacy-events`;
- `FollowClient` (fail closed), `FriendshipClient` (fail closed, forwards viewer token),
  `BlockClient` (fail open), with `PooledRequestFactory` and the internal-token header.

Answers that are the same for every story of one owner (friendship, follow, block) are asked once
per owner per request, not per story.

### 5.4 API (`/api/v1/stories`)

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/upload-url` | user | §5.2 |
| POST | `/` | user | `{mediaUrl, caption?, visibility}`; caption 422 on severity 3; returns owner view |
| GET | `/feed?followedUserIds=` | user | grouped by author (below); ids from the client like the video following feed, each one still checked by §5.3 |
| GET | `/users/{userId}` | optional | that owner's stories visible to the requester, oldest first; owner gets all with status |
| GET | `/{storyId}` | optional | 404 when not visible |
| PATCH | `/{storyId}/visibility` | owner | |
| POST | `/{storyId}/views` | user | existing; see §5.6 |
| GET | `/{storyId}/viewers` | owner | existing, add cursor paging |
| DELETE | `/{storyId}` | owner | existing soft delete |

Feed response: `[{userId, stories: [StoryResponse…], hasUnseen}]`, the viewer's own group first,
then groups with unseen stories, each ordered by latest story. `StoryResponse` gains `status`
(owner only, null otherwise), `visibility`, `viewedByMe`, `durationSeconds`. `viewedByMe` comes from
one `story_views` query on `(viewerId, storyId in …)` — add that index.

Cap `followedUserIds` like the video feed does (`TooManyFollowedUsersException`).

### 5.5 Moderation

- **Caption**: `moderation-lib` at create time, the way `CaptionModeration` does it for videos.
  Severity 3 → 422 naming the field, nothing saved. Severity 2 → `textVerdict = REVIEW` on the
  event. Classifier fail-open, as everywhere else.
- **Media** in media-worker, a new consumer of `story.story-events` (own consumer group), reusing
  `ModerationClient`, `Ffmpeg`, `VideoProbe`, the retry loop and `CaptionVerdict.combine`:
  - image: the uploaded bytes are the one frame, sent as-is — moderation-service decodes with
    `PIL.Image.open(...).convert("RGB")` (`app.py`), so JPEG/PNG/WebP all work without ffmpeg;
  - video: probe first; longer than 60s → REJECTED with a reason, no classifier call; otherwise
    frames sampled across the whole video as for videos. The probed `durationSeconds` rides on
    `StoryModerationCompletedEvent` and is stored on the story (the viewer's progress bar needs it).
  - Never throws; exhausted retries → REVIEW (→ HELD). Never "pass on failure".
  - No inbox: it keeps no state; a redelivery re-scores the same file.
- **Result in story-service**: conditional update `status = PENDING_MODERATION → X` (the update's
  match count is the claim; a redelivery matches nothing). Scores are stored on the story
  (`moderation` sub-document like `VideoModeration`) for threshold tuning.
- `moderation.enabled=false` in media-worker approves, as for videos.

### 5.6 Things to fix in the existing code

- `recordView`: check-then-insert races into a duplicate-key 500, and the `save()` of the whole
  story with `@Version` loses or fails on concurrent views. Insert the view relying on the unique
  index (duplicate = already counted, return), then `$inc` `viewCount` atomically.
- `application.yml`: Mongo URI from env; `spring.data.mongodb.auto-index-creation: true` if not set.
- `SecurityConfig`: match the table in §5.4; internal-token wiring where clients need it.

### 5.7 Wiring and events

- `event-schema`: `StoryCreatedEvent` (eventId, storyId, userId, mediaKey, mediaType, textVerdict,
  occurredAt) on `story.story-events`, key `storyId`; `StoryModerationCompletedEvent` (shape of
  `VideoModerationCompletedEvent` with `storyId`) on `media.story-moderation-events`. Add both to
  `EventJsonCompatibilityTest`.
- story-service outbox: per-document flags + `OutboxDispatcher` + `OutboxBacklog` bean, as
  `VideoEventPublisher` (no Mongo replica set → no transactions).
- story-service adds `kafka-lib`, `moderation-lib`, MinIO client.
- Gateway: route `/api/v1/stories/**` → story-service.
- `docker-compose.prod.yml` (and local compose if services are listed there): add story-service
  with the shared Java env (JWT_SECRET, INTERNAL_API_TOKEN, Kafka, Mongo, MinIO, moderation-lib).
- Docs: `docs/story-service-api.md` (new); root `CLAUDE.md` lines that say story has no consumer
  or outbox, and the kafka-lib user lists.

## 6. Web (`tiktok-cloned`)

Follow `AGENTS.md` and `FRONTEND_RULES.md`: one API client, `src/lib/api/stories.ts`; pure logic
(grouping, next/previous story, progress) in `src/lib/stories/` with unit tests; hooks kebab-case;
match the existing visual language of the app.

- **Tray** at the top of the Following page (`FollowFeed`), signed-in only: the viewer's own bubble
  first (with "+" to post), then followed accounts with stories. Ring colored when `hasUnseen`,
  grey when all seen. Followed ids come from `getFollowingIds`, as `useFollowFeed` does. No tray
  when nobody has stories and the viewer has none.
- **Viewer**: full-screen overlay. Segmented progress bar per story; image 5s, video its own
  length; click right/left half = next/previous, at the end of one author go to the next author;
  Esc / close button. Header: avatar, name, relative time. Video plays muted-until-tapped if
  autoplay rules require it. Sends `POST /views` once per story shown.
- **Owner view** (own stories): status badge for pending / held / rejected, view count, viewers
  list (profiles hydrated with `getProfiles`), change visibility, delete.
- **Post**: from the "+" bubble, a modal: pick file (type/size checked client-side against §2's
  limits), preview, caption, visibility selector reusing the upload page's control, post. Upload
  progress reuses the XHR upload in `lib/api/videos.ts` (generalize it rather than copy it). A
  caption 422 shows under the field. After posting, the own bubble shows the pending story.
- **Profile**: avatar ring on `/[username]` when that owner has stories visible to the viewer;
  clicking opens the viewer.

## 7. Tests (one runnable check per non-trivial piece)

- story-service: visibility matrix (§5.3) unit test; moderation result transition incl.
  redelivery; `recordView` duplicate; upload key ownership.
- media-worker: story consumer — image, video, too long, classifier down → REVIEW.
- event-schema compatibility entries.
- web: unit tests for `src/lib/stories/*`; `npm run check`.

## 8. Left to the builder

Class names, exact DTO fields beyond the above, viewer animation details, how the tray scrolls on
narrow screens, whether `/feed` paginates (unpaged is fine while the number of followed accounts
with live stories is small — mark with a `ponytail:` comment).
