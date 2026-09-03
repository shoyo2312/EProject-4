# Upload limits: video size & duration

**Date:** 2026-08-30
**Branch:** `feat/upload-limits` (from `dev`)
**Repos touched:** `tiktok-backend` (video-service, media-worker), `tiktok-cloned` (frontend)

## 1. Problem

video-service currently enforces no limit on uploaded video size or duration:

- `createUploadUrl` hands out a presigned **PUT** URL. S3 query-signing covers
  the object key and expiry only, so the client can PUT a file of any size or
  type to that key.
- Nothing decodes the file, so duration is unknown — `TranscodeResult`
  hardcodes `durationSeconds` to `null` and the `Video.durationSeconds` column
  is never populated by the backend.
- `CreateVideoRequest` validation covers title (≤150), description (≤2000),
  tags and the `rawFileUrl` host/bucket — never size or duration.
- The frontend has a soft 2 GB client-side check (`UploadPage.tsx`,
  `MAX_BYTES`) that is advisory only and bypassable.

Consequences: a single upload can fill the MinIO bucket or hand media-worker an
hour-long file to transcode; the feed can contain arbitrarily long videos.

## 2. Goals

1. Enforce a maximum file size at the storage edge (MinIO rejects the upload
   itself) **and** as a backstop in media-worker.
2. Enforce a maximum duration in media-worker (the only component that decodes
   the file).
3. When the backstop rejects a file, tell the uploader **why** — not a generic
   "transcoding failed".
4. Frontend: block over-limit files before the upload starts, and show a live
   character counter for title/description plus the real size/duration limits.

Non-goals: adaptive-bitrate transcoding, thumbnail generation, per-account
quota, changing the title/description limits (already 150/2000 and correct).

## 3. Limits

| Limit | Value | Env var |
|---|---|---|
| Max file size | 500 MB (`524288000` bytes) | `VIDEO_MAX_BYTES` |
| Max duration | 10 min (`600` seconds) | `VIDEO_MAX_DURATION_SECONDS` |

Both services read the **same env var names** with the same defaults, so the
storage-edge limit and the media-worker backstop cannot drift. The frontend
mirrors the values as constants, matching the existing pattern in
`tiktok-cloned/src/lib/forms/schemas.ts` (which already mirrors backend DTO
limits deliberately).

## 4. Design

### 4.1 media-worker — enforce size + duration (backstop)

**Config.** New block, mirroring the existing `media.avatar`:

```yaml
media:
  video:
    max-bytes: ${VIDEO_MAX_BYTES:524288000}
    max-duration-seconds: ${VIDEO_MAX_DURATION_SECONDS:600}
```

New `MediaVideoProperties` record (`@ConfigurationProperties(prefix = "media.video")`),
registered the same way the existing avatar config is.

**Dependency.** Add to `media-worker/pom.xml`:

```xml
<dependency>
  <groupId>ws.schild</groupId>
  <artifactId>jave-all-deps</artifactId>
  <version>3.5.0</version>
</dependency>
```

`jave-all-deps` bundles ffmpeg/ffprobe native binaries for linux/mac/win, so no
per-machine install is needed on mixed dev environments. (Leaner alternative:
`jave-core` + a per-OS `jave-nativebin-*`; rejected for simplicity here.)

**`TranscodeServiceImpl.transcode(videoId, rawFileUrl)`** — before the
server-side MinIO copy:

1. `long size = minioClient.statObject(...).size();`
   If `size > props.maxBytes()` → `throw new MediaRejectedException(...)`
   with message `"Video is 812 MB; the maximum is 500 MB."`
2. Build a presigned **GET** URL for `sourceKey` with a short fixed expiry
   (5 min constant — media-worker's `minio` config has no `url-expiry` key,
   unlike video-service). Pass it to JAVE2:
   `new MultimediaObject(new URL(presignedGet)).getInfo().getDuration()`
   (milliseconds). ffprobe reads the container header, not the whole file, so
   the worker heap is not loaded with the payload.
   `int durationSeconds = (int) Math.round(durationMs / 1000.0);`
3. If `durationSeconds > props.maxDurationSeconds()` →
   `throw new MediaRejectedException(...)` with message
   `"Video is 12m30s; the maximum is 10m00s."`
4. Proceed with the copy. Return `new TranscodeResult(thumbnailUrl, hlsUrl,
   durationSeconds)` — `durationSeconds` is now a real measurement instead of
   `null`.

**`MediaRejectedException`** — new class in
`com.tiktok.mediaworker.service`, extends `RuntimeException`, carries only the
human-readable message. It means "this file is permanently unacceptable",
distinct from "storage was briefly unreachable".

**`VideoEventConsumer.transcodeWithRetries(event)`** — split the catch:

```java
try {
    TranscodeResult result = transcodeService.transcode(...);
    return VideoTranscodedEvent.success(...);
} catch (MediaRejectedException e) {
    log.warn("Rejecting video {}: {}", event.videoId(), e.getMessage());
    return VideoTranscodedEvent.failure(event.videoId(), e.getMessage()); // no retry
} catch (Exception e) {
    // unchanged: up to `transcodeAttempts`, pause between, then failure
}
```

A rejected file must not spend 3 attempts; a transient IO error still does.

### 4.2 video-service — surface the failure reason

`VideoTranscodedEvent` already carries `failureReason` and media-worker already
populates it; video-service currently drops it on the floor.

- **`Video` entity** — add nullable `String failureReason`.
- **`Video.markFailed()`** → **`markFailed(String reason)`**; stores the reason
  alongside `status = FAILED`.
- **`VideoTranscodedEventConsumer`** — the failure branch calls
  `videoStateUpdater.apply(videoId, v -> v.markFailed(event.failureReason()),
  videoRepository::updateFailed, EVENT)`.
- **`VideoRepository`** — new `updateFailed(Video)` that persists `status` +
  `failureReason` (the existing `updateStatus` only sets `status`).
- **`VideoResponse`** — add `String failureReason` (null unless `FAILED`).
  MapStruct maps it by name automatically.
- MongoDB — no migration; the field is simply absent on existing documents.

### 4.3 video-service — MinIO POST policy (storage-edge size limit)

**Config.** New block:

```yaml
app:
  upload:
    max-bytes: ${VIDEO_MAX_BYTES:524288000}
    max-duration-seconds: ${VIDEO_MAX_DURATION_SECONDS:600}
```

`max-duration-seconds` is not enforced here (video-service never sees the
bytes); it is carried so the value has a single home per service and can be
echoed in docs / an error message. New `UploadLimitProperties` record.

**`createUploadUrl(userId, request)`** — replace the presigned PUT with a
presigned POST:

```java
PostPolicy policy = new PostPolicy(bucket, ZonedDateTime.now().plus(urlExpiry));
policy.addEqualsCondition("key", objectKey);
policy.addContentLengthRangeCondition(1, uploadLimits.maxBytes());
policy.addStartsWithCondition("Content-Type", "video/");
Map<String, String> formData = minioClient.getPresignedPostFormData(policy);
// formData does NOT include "key" or "Content-Type" — caller adds those as form parts
```

MinIO enforces `content-length-range` when the browser POSTs; an oversize file
is refused by storage with a 400 before any bytes are stored.

**`UploadUrlResponse` contract change:**

```java
public record UploadUrlResponse(
    String uploadUrl,                 // bucket URL to POST the multipart form to
    Map<String, String> formFields,   // policy, x-amz-*, plus key + Content-Type
    String fileUrl,                   // unchanged: s3://bucket/key for CreateVideoRequest
    long expiresInSeconds
) {}
```

The service adds `key` and `Content-Type` into `formFields` so the client can
treat it as an opaque "append every entry, then the file".

Remove the now-satisfied `ponytail:` comment at `VideoServiceImpl.java:97`.

### 4.4 Frontend — upload contract (`src/lib/api/videos.ts`)

- `UploadUrlResponse` TS type — add `formFields: Record<string, string>`.
- `uploadToStorage(uploadUrl, file, onProgress, signal)` — rewrite the XHR:
  - `xhr.open("POST", uploadUrl)` (was `PUT`)
  - body is a `FormData`: every `formFields` entry first, then
    `form.append("file", file)` **last** (S3 POST requires the file field
    last).
  - no `Content-Type` header set on the request (the browser sets the
    multipart boundary); the stored object's content-type comes from the
    `Content-Type` form field.
  - success is `xhr.status` 200/201/204.
  - `xhr.upload.onprogress` unchanged — progress still works.

### 4.5 Frontend — client-side pre-check (`src/components/upload/UploadPage.tsx`)

- Constants mirror the backend:
  ```ts
  const MAX_BYTES = 500 * 1024 * 1024;
  const MAX_DURATION_SECONDS = 600;
  ```
- `chooseFile`:
  - size check message → `"That file is over 500 MB."`
  - new duration check: `readDuration(file)` resolves the file's length via a
    detached `HTMLVideoElement` + `loadedmetadata` (revoking its object URL
    after). If `> MAX_DURATION_SECONDS`, set
    `"That video is longer than 10 minutes."` and do not accept the file.
  - `chooseFile` becomes async (or chains a `.then`); the drop-zone and
    replace-input call sites await it.
- Submit failure branch: when `latest.status === "FAILED"`, show
  `latest.failureReason ?? "Transcoding failed. Try uploading the file again."`

### 4.6 Frontend — character counter + limits copy (`UploadPage.tsx`)

- `TextField` — render a `value.length / maxLength` counter beneath the field
  when `maxLength` is set:
  - right-aligned, `text-[13px]`, `--tt-text-secondary`; switches to
    `--tt-red-active` when `value.length >= maxLength`.
  - wired with `aria-describedby` to the field.
  - `maxLength` already caps input, so the counter never exceeds max — it is a
    fill indicator, not a validator.
- `DropZone` `<Fact term="Size and duration">` — replace
  "Up to 2 GB. Longer clips just take longer to transcode." with
  "Up to 500 MB and 10 minutes."
- `schemas.ts` `uploadSchema` — unchanged (title/description already 150/2000).

## 5. Testing

### media-worker
- `TranscodeServiceImplTest`
  - `statObject` size over limit → `MediaRejectedException`, no copy performed.
  - ffprobe duration over limit → `MediaRejectedException`.
  - within both limits → `TranscodeResult` with `durationSeconds` populated.
  - fixture: a small real `.mp4` (a few hundred KB, a few seconds) in
    `src/test/resources`; JAVE2 runs fully offline.
- `VideoEventConsumerTest`
  - `MediaRejectedException` → exactly one `VideoTranscodedEvent.failure`, no
    retry, `failureReason` == the exception message.
  - `IOException` → `transcodeAttempts` attempts, then a `failure` event.

### video-service
- `VideoTranscodedEventConsumerTest` — a failure event persists both
  `status = FAILED` and `failureReason`.
- `VideoServiceImplTest` — `createUploadUrl` returns non-empty `formFields`
  including a content-length-range condition and `key`; `fileUrl` unchanged
  shape (`s3://bucket/raw/{userId}/{id}.{ext}`).
- Mapper test — `failureReason` propagates into `VideoResponse`.
- Fix `VideoServiceImplUploadUrlFailureTest` for the new MinIO call.

### frontend (tiktok-cloned)
- Pre-check rejects a >500 MB file and a >10 min file with the right messages.
- `TextField` counter renders `"3 / 150"` and goes red at the limit.
- FAILED submit shows `failureReason` when present, the fallback otherwise.
- `uploadToStorage` builds a `FormData` with the file appended last and still
  reports progress.

## 6. Rollout / operational notes

- `jave-all-deps` adds ~40–130 MB of jars to media-worker (all platforms
  bundled). Acceptable for this project; revisit with per-OS nativebin if the
  artifact size becomes a problem.
- No database migration (MongoDB).
- New env vars `VIDEO_MAX_BYTES` and `VIDEO_MAX_DURATION_SECONDS` — document in
  both services' README/`application.yml` comments and any deployment env
  template.
- Update `docs/` upload walkthrough and the Postman collection's
  `UploadUrlResponse` example for the PUT→POST change.
- Backwards compatibility: the `UploadUrlResponse` change is breaking for any
  client of `POST /api/v1/videos/upload-url`. The only known client is
  `tiktok-cloned`, updated in the same change. `tiktok_mobile` / `tiktok-admin`
  do not upload videos.
