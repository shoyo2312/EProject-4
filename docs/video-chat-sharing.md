# Share a video in a direct message

Use the existing conversation send endpoint, one request per recipient. A caption and video
are one message for every existing send policy, request quota, and 15-day retry rule.

## Send

`POST /api/v1/conversations/{conversationId}/messages` (authenticated user), or STOMP SEND to
`/app/chat.send/{conversationId}` with the same body:

```json
{"type":"VIDEO","videoId":"123","content":"  Watch this  ","clientRequestId":"share-123-recipient-2"}
```

`videoId` must be non-blank and at most 64 characters. `content` is optional; after trimming,
blank means null and the maximum is 300 Java UTF-16 units. `stickerId` must be null.
`clientRequestId` is optional, at most 64 characters, scoped to conversation and sender.
`replyToMessageId` may be supplied using the existing reply rules. Client-supplied card
metadata is ignored. TEXT/STICKER requests cannot carry a videoId.

A newly stored message returns HTTP 201. An existing `(conversationId, senderId, clientRequestId)`
returns HTTP 200 with the same MessageResponse shape and message id. It does not broadcast,
change the conversation, consume another request slot, or count another share. The card is still
resolved for the viewer at the time of the retry. The normal ApiResponse timestamp is per request.

Example response (the envelope timestamp and ids are illustrative):

```json
{
  "success":true,
  "data":{
    "id":"987","conversationId":"c1","senderId":1,"content":"Watch this",
    "sentAt":"2026-10-28T12:00:00Z","type":"VIDEO","stickerId":null,"sticker":null,
    "replyToMessageId":null,"replyTo":null,"recalledAt":null,"reactions":[],"revision":0,
    "video":{"videoId":"123","state":"AVAILABLE","ownerId":7,"thumbnailUrl":"https://cdn/thumb.jpg","title":"Video title","durationSeconds":12}
  },
  "timestamp":"2026-10-28T12:00:00Z"
}
```

Only fields present in VideoResponse are exposed: ownerId comes from userId. There is no ownerName
or playback URL. A missing/null card is omitted from JSON, preserving older TEXT/STICKER shapes.
Unavailable and outage cards contain no metadata:

```json
{"videoId":"123","state":"UNAVAILABLE"}
```

```json
{"videoId":"123","state":"UNKNOWN"}
```

A VIDEO reply quote has type VIDEO, a caption shortened with the existing 140-unit quote rule,
and a minimal card: videoId, state, thumbnailUrl when available; otherwise state only.
Recalled messages and quotes carry neither caption nor card. Recall also unsets videoId in Mongo.
Conversation previews store type VIDEO and null content; captions never enter previews.

Cards are hydrated on every render, separately for each viewer, including REST pages, send responses,
private socket frames, quotes, and exports. Chat-side cache TTL is zero. Every page combines its
messages and reply originals into one hydration operation; the client sends batches of at most
50 distinct ids. A page with more than 50 distinct ids needs multiple HTTP calls to obey the fixed
internal cap (history/export can return 100 messages, plus quoted originals). There are no per-message
lookups. VIDEO messages and replies go only to each participant's `/user/queue/chat`, never the shared
conversation topic. Existing video-service caches and their status-change eviction still apply.

## Send errors

- 400 INVALID_MESSAGE: malformed VIDEO payload, caption too long, contradictory fields.
- 400 VALIDATION_ERROR: Bean Validation constraints, including ids longer than 64 characters.
- 404 VIDEO_NOT_AVAILABLE: sender cannot view the video.
- 422 VIDEO_NOT_SHAREABLE_TO_RECIPIENT: another participant cannot view the video.
- 503 VIDEO_CHECK_UNAVAILABLE: authoritative availability check failed; send fails closed.

Existing block, DM settings, request, reply, hide, reaction, and membership errors retain their rules.
An outage while rendering a stored message produces UNKNOWN, rather than pretending it was deleted.

## Internal viewability

`GET /api/v1/videos/internal/viewable?viewerId=1&ids=123,456`

Requires `X-Internal-Token`; neither an anonymous caller nor a user JWT can choose viewerId.
The gateway already denies `/api/v1/videos/internal/**`. The list is capped at 50 entries.
The response contains only viewable videos, in requested order; missing ids are omitted:

```json
{"success":true,"data":[{"id":"123","userId":7,"thumbnailUrl":"https://cdn/thumb.jpg","title":"Video title","durationSeconds":12}],"timestamp":"2026-10-28T12:00:00Z"}
```

The endpoint reuses getByIds audience/follower/friend rules, requires PUBLISHED and a live row,
filters banned/deactivated owners, and checks bidirectional blocks once per distinct visible owner.
An owner may not share unpublished or hidden-owner content. Title uses the real title field,
falling back to description, trimmed and limited to 80 UTF-16 units without splitting a surrogate.
The existing BlockClient failure policy is retained. The policy endpoint is not consulted.

## Internal share counting

`POST /api/v1/interactions/internal/videos/123/shares`

Requires `X-Internal-Token`; the existing gateway denyAll covers this prefix too. Body:

```json
{"userId":1,"dedupeKey":"987"}
```

`userId` is required; dedupeKey is non-blank, at most 64 characters, and is the stored chat message id.
Both an applied claim and a duplicate return HTTP 200:

```json
{"success":true,"data":{"counted":true},"timestamp":"2026-10-28T12:00:00Z"}
```

```json
{"success":true,"data":{"counted":false},"timestamp":"2026-10-28T12:00:01Z"}
```

Cassandra table chat_share_dedupe uses a composite partition key `(video_id, dedupe_key)` and
INSERT IF NOT EXISTS with a 30-day TTL (2592000 seconds). Only a new claim writes ShareByVideo,
increments the counter, invalidates cache, and publishes VideoSharedEvent, using the public share
implementation's persistence/compensation path. Internal shares skip public visibility and rate
limiter checks. Failures compensate the share and delete the claim so an explicit internal retry
can count. The public `/videos/{id}/share` behavior is unchanged.

Chat calls stats once after storage and delivery, with 300ms connect and 500ms read timeouts.
Stats failures are WARN logs and do not fail/repeat delivery. There is no automatic stats retry:
a failed best-effort call can leave a share uncounted, as required by the send contract.

The schema is in scripts/cassandra-init.cql and the test initialization schema. Existing clusters
must apply the added CREATE TABLE statement; the INSERT also supplies TTL even if the table was
created by Spring Data schema creation without default_time_to_live.

## Storage and export

Mongo's unique message_client_request_idx is on conversationId, senderId, clientRequestId, with
partialFilterExpression `{clientRequestId: {$exists: true}}`. This gives sparse participation.
It does not set sparse=true as Mongo forbids combining sparse and partialFilterExpression.
The existing auto-index-creation=true enables creation. The one_request_message_idx remains intact.
No snapshot of a card is stored; nullable videoId and clientRequestId are the only new message fields.

The existing internal export endpoint returns VIDEO rows with identifiers, caption, and sentAt,
without cards or media URLs:

```json
{"id":"987","conversationId":"c1","senderId":1,"type":"VIDEO","videoId":"123","content":"Watch this","sentAt":"2026-10-28T12:00:00Z","recalledAt":null}
```

TEXT/STICKER export JSON is unchanged. A reply to a video has its card stripped from export too.
The user-service consumer retains videoId and caption, writes Type=VIDEO and Video ID in the
archive, and supplies the required viewerId when fetching conversation history.

## Concurrent branch integration

The other agent owns ConversationViews and ConversationResponse. Its fallback preview path for
hidden/cleared conversations must also suppress VIDEO captions: use
`latest.isRecalled() || latest.getType() == MessageType.VIDEO ? null : latest.getContent()` when
rebuilding the preview, or enforce the same rule in ConversationResponse.withPreview. Those files
are intentionally untouched here. Until that change is integrated, the fallback can expose a
caption even though recordMessage stores a null preview. This is a required merge check.
