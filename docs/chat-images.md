# Chat images (IMAGE messages)

One picture per message, with an optional caption. Two steps, both through the gateway under
`/api/v1/conversations/{conversationId}`:

1. `POST /images` (multipart, field `file`) → `201 { imageId, url, mime, width, height, size }`.
   The upload belongs to the caller and expires after 1 hour unless a message claims it.
2. `POST /messages` (or STOMP `/app/chat.send/{id}`) with
   `{ "type": "IMAGE", "imageId": "...", "content": "optional caption ≤ 1000", "clientRequestId": "..." }`.
   The same `clientRequestId` returns the first message (200) instead of a second one, so a retry after a
   lost response cannot post twice. The image is claimed atomically (`ChatImageRepository.claim`): only the
   uploader, only in this conversation, only once.

## Rules enforced on the server

- Type is read from the file signature, never from the declared content type: JPEG, PNG, GIF only
  (`IMAGE_UNSUPPORTED_TYPE`). SVG, HTML, WebP and the rest are refused.
- At most 5 MB (`IMAGE_TOO_LARGE`), at most 8000 px a side and 40 MP (`IMAGE_DIMENSIONS`), and it must really
  decode (`IMAGE_CORRUPT`) — read at a coarse subsampling so a small file with a huge canvas cannot exhaust memory.
- `width`/`height` are display size: a JPEG whose EXIF orientation is 5–8 has them swapped.
- Uploading follows the sending rules: blocked pair → `MESSAGING_BLOCKED`, pending request nobody accepted →
  `MESSAGE_REQUEST_NOT_ACCEPTED`, request message already spent → `MESSAGE_REQUEST_LIMIT_REACHED`.
- Spring multipart limits: `spring.servlet.multipart.max-file-size=6MB` (chat-service `application.yml`).

## Access

Bytes live in chat-service's Mongo (`chat_images`, TTL index on `expiresAt` for unclaimed uploads), not in a
public bucket. `GET /images/{imageId}` needs the viewer's bearer token (so an `<img src>` cannot be used: the web
client fetches a blob) and answers 404 for: another conversation's id, a recalled message, a message deleted from
the viewer's side or covered by deleting the conversation, and an unsent upload that is not the caller's.
Non-participants get 403. Responses are `Cache-Control: no-store`, `nosniff`, CSP `default-src 'none'; sandbox`.

## Behaviour of the rest of chat for IMAGE

- Realtime: same frames as any message (shared topic; per-viewer queue when it replies, because quotes differ per viewer).
- Preview / export: type `IMAGE`, **no caption in the conversation preview** (like VIDEO), so a recall or hide cannot leave it behind.
- Reply: the quote carries the picture's metadata (`replyTo.image`) and the caption; the client draws a thumbnail.
- Recall: the message loses `imageId`/`content` and the image row is deleted, so the bytes cannot be read again.
- Reactions, receipts, hide: unchanged.

## Operations

- No migration. Mongo creates `chat_images` and its indexes (`auto-index-creation` is already on).
- WebSocket origins: `WEBSOCKET_ALLOWED_ORIGINS` must list every web origin in use (default is only `http://localhost:3000`).
- Storage grows with chat images in the chat database; backup already covers Mongo.
