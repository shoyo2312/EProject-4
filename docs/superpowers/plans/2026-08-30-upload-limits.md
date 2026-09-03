# Upload Limits (video size & duration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce a 500 MB / 10-minute ceiling on video uploads — at the MinIO storage edge and as a media-worker backstop — and surface the rejection reason to the uploader; add a live character counter and correct limit copy to the frontend upload form.

**Architecture:** media-worker gains a `VideoProbe` seam (JAVE2/ffprobe) and rejects oversize/over-long files with a non-retryable `MediaRejectedException` that becomes a `VideoTranscodedEvent.failure(reason)`. video-service switches `createUploadUrl` from a presigned PUT to a presigned POST policy carrying `content-length-range`, and plumbs the existing `VideoTranscodedEvent.failureReason` through the `Video` entity into `VideoResponse`. The Next.js frontend adapts to the POST contract, pre-checks size and duration in the browser, and shows the failure reason and a char counter.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring Kafka, MongoDB (Spring Data), minio-java 8.5.12, `ws.schild:jave-all-deps` 3.5.0, MapStruct; Next.js / React / TypeScript / Zod (frontend, no unit-test runner — verification is `npm run typecheck` + `lint` + `build` + manual smoke).

**Spec:** `docs/superpowers/specs/2026-08-30-upload-limits-design.md`

## Global Constraints

- Max file size: **500 MB = `524288000` bytes**, env var `VIDEO_MAX_BYTES`.
- Max duration: **10 min = `600` seconds**, env var `VIDEO_MAX_DURATION_SECONDS`.
- Both services read the **same env var names with the same defaults**; the frontend mirrors the values as literal constants.
- Java: constructor injection only (`@RequiredArgsConstructor` or explicit constructor), never `@Autowired` on a field.
- `Video` is a Mongo `@Document` with `@Getter @SuperBuilder @NoArgsConstructor @AllArgsConstructor` — do not add `@Data`/`@Setter`.
- No MongoDB migration (new field is absent on old docs → maps to `null`).
- MongoDB service keeps `spring.data.mongodb.auto-index-creation: true` (already set) — no index changes in this plan.
- Kafka consumers stay idempotent; do not touch the `IdempotentEventProcessor` claim flow.
- Frontend: no new dependency, no test framework. Keep `npm run check` green.
- Commit after every task with a Conventional Commit message; end backend commit bodies with the repo's `Co-Authored-By:` / `Claude-Session:` trailers as other commits do.

---

## File Structure

**media-worker** (`services/media-worker`)
- `src/main/java/com/tiktok/mediaworker/service/VideoProbe.java` — *new*, interface: `int durationSeconds(String httpUrl)`.
- `src/main/java/com/tiktok/mediaworker/service/JaveVideoProbe.java` — *new*, `@Component` impl using `ws.schild.jave`.
- `src/main/java/com/tiktok/mediaworker/service/MediaRejectedException.java` — *new*, `RuntimeException`, message only.
- `src/main/java/com/tiktok/mediaworker/config/MediaVideoProperties.java` — *new*, `@ConfigurationProperties(prefix = "media.video")` record `{ long maxBytes, int maxDurationSeconds }`.
- `src/main/java/com/tiktok/mediaworker/service/TranscodeServiceImpl.java` — *modify*: inject `MediaVideoProperties` + `VideoProbe`; add size + duration guards; return real `durationSeconds`.
- `src/main/java/com/tiktok/mediaworker/event/consumer/VideoEventConsumer.java` — *modify*: split the catch so `MediaRejectedException` fails once with no retry.
- `src/main/java/com/tiktok/mediaworker/MediaWorkerApplication.java` — *modify*: register `MediaVideoProperties` via `@EnableConfigurationProperties`.
- `src/main/resources/application.yml` — *modify*: add `media.video` block.
- `pom.xml` — *modify*: add `jave-all-deps`.
- `src/test/java/com/tiktok/mediaworker/service/TranscodeServiceImplTest.java` — *modify*: new ctor args, duration assertion, guard tests.
- `src/test/java/com/tiktok/mediaworker/service/JaveVideoProbeTest.java` — *new*: probe a committed fixture.
- `src/test/java/com/tiktok/mediaworker/event/consumer/VideoEventConsumerTest.java` — *modify*: add the no-retry-on-rejection test.
- `src/test/resources/fixtures/sample-3s.mp4` — *new*: a real ~3-second clip (a few hundred KB).

**video-service** (`services/video-service`)
- `src/main/java/com/tiktok/videoservice/entity/Video.java` — *modify*: add `failureReason`; `markFailed()` → `markFailed(String reason)`.
- `src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java` — *modify*: add `boolean updateFailed(Video, VideoStatus)`.
- `src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java` — *modify*: implement `updateFailed`.
- `src/main/java/com/tiktok/videoservice/event/consumer/VideoTranscodedEventConsumer.java` — *modify*: failure branch passes the reason and uses `updateFailed`.
- `src/main/java/com/tiktok/videoservice/dto/response/VideoResponse.java` — *modify*: add `String failureReason`.
- `src/main/java/com/tiktok/videoservice/dto/response/UploadUrlResponse.java` — *modify*: add `Map<String,String> formFields`.
- `src/main/java/com/tiktok/videoservice/config/UploadLimitProperties.java` — *new*, `@ConfigurationProperties(prefix = "app.upload")` record `{ long maxBytes, int maxDurationSeconds }`.
- `src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java` — *modify*: inject `UploadLimitProperties`; `createUploadUrl` builds a presigned POST; drop the obsolete `ponytail:` comment.
- `src/main/java/com/tiktok/videoservice/VideoServiceApplication.java` — *modify*: add `UploadLimitProperties` to `@EnableConfigurationProperties`.
- `src/main/resources/application.yml` — *modify*: add `app.upload` block.
- `src/test/java/com/tiktok/videoservice/event/consumer/VideoTranscodedEventConsumerTest.java` — *modify*: assert `failureReason` persisted.
- `src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java` — *modify*: new ctor arg, mock the POST call.
- `src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java` — *modify*: new ctor arg.
- `src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java` — *modify*: add a `createUploadUrl` POST-policy test.

**frontend** (`tiktok-cloned/`, sibling of `tiktok-backend/`)
- `src/lib/api/types.ts` — *modify*: `UploadUrlResponse` gains `formFields`; `VideoResponse` gains `failureReason`.
- `src/lib/api/videos.ts` — *modify*: `uploadToStorage` → multipart POST.
- `src/components/upload/UploadPage.tsx` — *modify*: constants, `readDuration`, pre-check, FAILED reason, `TextField` counter, `<Fact>` copy.

**docs**
- `docs/video-service-api.md` — *modify*: upload flow + `UploadUrlResponse` shape.
- `postman/` collection — *modify*: `upload-url` example response.

---

## Task 1: video-service — plumb `failureReason` from event to API

**Files:**
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/entity/Video.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/event/consumer/VideoTranscodedEventConsumer.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/dto/response/VideoResponse.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/event/consumer/VideoTranscodedEventConsumerTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `Video.markFailed(String reason)` — sets `failureReason` then applies `FAILED` outcome.
  - `Video.getFailureReason()` → `String` (Lombok `@Getter`).
  - `VideoRepositoryCustom.updateFailed(Video video, VideoStatus expectedStatus)` → `boolean` (compare-and-set on `status`, writing `status` + `statusBeforeTakedown` + `failureReason`).
  - `VideoResponse` record gains a trailing component `String failureReason`.

- [ ] **Step 1: Add the failing assertion to the existing consumer test**

In `VideoTranscodedEventConsumerTest.java`, replace the body of
`onMessage_failure_marksVideoFailed`:

```java
    @Test
    void onMessage_failure_marksVideoFailed() throws Exception {
        Video video = videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/2.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build());

        VideoTranscodedEvent event = VideoTranscodedEvent.failure(
                video.getId(), "Video is 12m30s; the maximum is 10m00s.");
        consumer.onMessage(objectMapper.writeValueAsString(event));

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(updated.getFailureReason())
                .isEqualTo("Video is 12m30s; the maximum is 10m00s.");
    }
```

Also, in `onMessage_failure_takenDownVideo_staysDown`, add after the existing asserts:

```java
        assertThat(after.getFailureReason()).isEqualTo("boom");
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `./mvnw test -pl services/video-service -Dtest=VideoTranscodedEventConsumerTest`
Expected: compile error `cannot find symbol: method getFailureReason()`.

- [ ] **Step 3: Add the field and change `markFailed` on the entity**

In `Video.java`, add next to `statusBeforeTakedown`:

```java
    /**
     * Why the last transcode attempt gave up — the message from media-worker's
     * VideoTranscodedEvent.failureReason. Shown to the uploader instead of a generic
     * "transcoding failed". Null for videos that never failed, and for ones that failed
     * before this field existed.
     */
    private String failureReason;
```

Replace `markFailed()`:

```java
    public void markFailed(String reason) {
        this.failureReason = reason;
        applyTranscodeOutcome(VideoStatus.FAILED);
    }
```

- [ ] **Step 4: Add `updateFailed` to the custom repository interface**

In `VideoRepositoryCustom.java`, add below `updateStatus`:

```java
    /**
     * A failed transcode: the status pair plus the reason it failed. Separate from
     * {@link #updateStatus} (takedown/restore) because only this path owns {@code failureReason} —
     * keeping the write field-scoped to what the operation owns, like every other method here.
     *
     * <p>Conditioned on the status the caller read, same as {@link #updateStatus}: a takedown can
     * land while the transcode is still running.
     *
     * @return false when the status moved underneath — re-read and re-apply
     */
    boolean updateFailed(Video video, VideoStatus expectedStatus);
```

- [ ] **Step 5: Implement `updateFailed`**

In `VideoRepositoryImpl.java`, next to `updateStatus`:

```java
    @Override
    public boolean updateFailed(Video video, VideoStatus expectedStatus) {
        return compareAndSet(video.getId(), expectedStatus, new Update()
                .set("status", video.getStatus())
                .set("statusBeforeTakedown", video.getStatusBeforeTakedown())
                .set("failureReason", video.getFailureReason()));
    }
```

- [ ] **Step 6: Wire the consumer's failure branch**

In `VideoTranscodedEventConsumer.java`, in `apply(...)`, replace the failure branch:

```java
        if (!event.success()) {
            log.warn("VideoTranscodedEvent failure for videoId={}: {}", event.videoId(), event.failureReason());
            videoStateUpdater.apply(event.videoId(),
                    video -> video.markFailed(event.failureReason()),
                    videoRepository::updateFailed,
                    EVENT);
            return;
        }
```

- [ ] **Step 7: Add `failureReason` to `VideoResponse`**

First `grep -rn "new VideoResponse(" services/video-service/src` — if any call builds
it positionally, note those files. Then in `VideoResponse.java` add a trailing component:

```java
        List<String> tags,
        Instant createdAt,
        /** Why the transcode failed; null unless {@code status == FAILED}. */
        String failureReason
) {
```

MapStruct maps by name — no `VideoMapper` change. Update any positional
`new VideoResponse(...)` call sites found above to pass a trailing argument.

- [ ] **Step 8: Run the video-service test suite**

Run: `./mvnw test -pl services/video-service`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add services/video-service
git commit -m "$(cat <<'EOF'
feat(video-service): surface transcode failure reason in VideoResponse

Plumb VideoTranscodedEvent.failureReason through Video.markFailed(reason)
and a new field-scoped VideoRepository.updateFailed into VideoResponse, so
the uploader sees why a video failed instead of a generic message.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0118HWvjKsTH1ZffhbSSvDfu
EOF
)"
```

---

## Task 2: media-worker — JAVE2 dependency + `VideoProbe` seam

**Files:**
- Modify: `services/media-worker/pom.xml`
- Create: `services/media-worker/src/main/java/com/tiktok/mediaworker/service/VideoProbe.java`
- Create: `services/media-worker/src/main/java/com/tiktok/mediaworker/service/JaveVideoProbe.java`
- Create: `services/media-worker/src/test/java/com/tiktok/mediaworker/service/JaveVideoProbeTest.java`
- Create: `services/media-worker/src/test/resources/fixtures/sample-3s.mp4`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `interface VideoProbe { int durationSeconds(String httpUrl); }` — rounds to nearest second; throws `IllegalStateException` if the media has no readable duration.
  - `@Component class JaveVideoProbe implements VideoProbe`.

- [ ] **Step 1: Add the JAVE2 dependency**

In `services/media-worker/pom.xml`, inside `<dependencies>`, after the `minio` entry:

```xml
        <dependency>
            <groupId>ws.schild</groupId>
            <artifactId>jave-all-deps</artifactId>
            <version>3.5.0</version>
        </dependency>
```

Run: `./mvnw -q dependency:resolve -pl services/media-worker`
Expected: resolves without error.

- [ ] **Step 2: Create a fixture clip**

Create a real ~3-second H.264 MP4 at
`services/media-worker/src/test/resources/fixtures/sample-3s.mp4`.

If `ffmpeg` is on PATH:

```bash
mkdir -p services/media-worker/src/test/resources/fixtures
ffmpeg -f lavfi -i testsrc=duration=3:size=160x120:rate=15 -pix_fmt yuv420p \
  services/media-worker/src/test/resources/fixtures/sample-3s.mp4
```

If not, copy any MP4 between 2 and 5 seconds long into that path and adjust the
assertion in Step 3 to its real length.

- [ ] **Step 3: Write the failing probe test**

`JaveVideoProbeTest.java`:

```java
package com.tiktok.mediaworker.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real ffprobe binary JAVE2 unpacks — no network, the input is a file URL.
 * The size/duration decision logic is unit-tested against a mocked VideoProbe elsewhere.
 */
class JaveVideoProbeTest {

    private final VideoProbe probe = new JaveVideoProbe();

    @Test
    void durationSeconds_readsTheLengthOfARealClip() throws Exception {
        Path fixture = Path.of(
                JaveVideoProbeTest.class.getResource("/fixtures/sample-3s.mp4").toURI());

        int seconds = probe.durationSeconds(fixture.toUri().toString());

        assertThat(seconds).isBetween(2, 4); // fixture is 3s
    }

    @Test
    void durationSeconds_throwsWhenTheUrlIsNotMedia() {
        assertThatThrownBy(() -> probe.durationSeconds("file:///no/such/file.mp4"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 4: Run it, verify it fails**

Run: `./mvnw test -pl services/media-worker -Dtest=JaveVideoProbeTest`
Expected: FAIL — `JaveVideoProbe` / `VideoProbe` do not exist.

- [ ] **Step 5: Create the interface**

`VideoProbe.java`:

```java
package com.tiktok.mediaworker.service;

/**
 * One question media-worker needs answered about an upload before it will transcode it:
 * how long is it. Split behind an interface so TranscodeServiceImpl's size/duration rules
 * are unit-testable without the ffprobe binary or a presigned URL.
 */
public interface VideoProbe {

    /**
     * @param httpUrl an {@code http(s)://} or {@code file://} URL ffprobe can open — for the
     *                real upload this is a short-lived presigned GET
     * @return the media duration rounded to the nearest second
     * @throws IllegalStateException if the URL yields no readable duration
     */
    int durationSeconds(String httpUrl);
}
```

- [ ] **Step 6: Create the implementation**

`JaveVideoProbe.java`:

```java
package com.tiktok.mediaworker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.info.MultimediaInfo;

import java.net.URL;

@Slf4j
@Component
public class JaveVideoProbe implements VideoProbe {

    @Override
    public int durationSeconds(String httpUrl) {
        try {
            MultimediaInfo info = new MultimediaObject(new URL(httpUrl)).getInfo();
            long millis = info.getDuration();
            if (millis <= 0) {
                throw new IllegalStateException("No readable duration for " + safe(httpUrl));
            }
            return (int) Math.round(millis / 1000.0);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // ws.schild throws a checked EncoderException plus MalformedURLException; to a
            // caller they mean the same thing — the file could not be probed.
            throw new IllegalStateException("Could not probe " + safe(httpUrl), e);
        }
    }

    /** Presigned URLs carry a signature query string; keep it out of logs and messages. */
    private static String safe(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?…";
    }
}
```

- [ ] **Step 7: Run the probe test, verify it passes**

Run: `./mvnw test -pl services/media-worker -Dtest=JaveVideoProbeTest`
Expected: PASS (first run unpacks the ffprobe binary).

- [ ] **Step 8: Commit**

```bash
git add services/media-worker/pom.xml \
  services/media-worker/src/main/java/com/tiktok/mediaworker/service/VideoProbe.java \
  services/media-worker/src/main/java/com/tiktok/mediaworker/service/JaveVideoProbe.java \
  services/media-worker/src/test/java/com/tiktok/mediaworker/service/JaveVideoProbeTest.java \
  services/media-worker/src/test/resources/fixtures/sample-3s.mp4
git commit -m "$(cat <<'EOF'
feat(media-worker): add VideoProbe seam backed by JAVE2 ffprobe

Introduces ws.schild:jave-all-deps and a VideoProbe interface returning a
clip's duration in seconds, with the real ffprobe exercised against a
committed 3s fixture. Nothing calls it yet.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0118HWvjKsTH1ZffhbSSvDfu
EOF
)"
```

---

## Task 3: media-worker — enforce size & duration in `TranscodeServiceImpl`

**Files:**
- Create: `services/media-worker/src/main/java/com/tiktok/mediaworker/service/MediaRejectedException.java`
- Create: `services/media-worker/src/main/java/com/tiktok/mediaworker/config/MediaVideoProperties.java`
- Modify: `services/media-worker/src/main/java/com/tiktok/mediaworker/MediaWorkerApplication.java`
- Modify: `services/media-worker/src/main/resources/application.yml`
- Modify: `services/media-worker/src/main/java/com/tiktok/mediaworker/service/TranscodeServiceImpl.java`
- Test: `services/media-worker/src/test/java/com/tiktok/mediaworker/service/TranscodeServiceImplTest.java`

**Interfaces:**
- Consumes: `VideoProbe.durationSeconds(String)` from Task 2.
- Produces:
  - `class MediaRejectedException extends RuntimeException` — constructed with a human-readable message.
  - `record MediaVideoProperties(long maxBytes, int maxDurationSeconds)`.
  - `TranscodeServiceImpl` constructor now `(MinioClient, MinioProperties, MediaVideoProperties, VideoProbe)` — Lombok `@RequiredArgsConstructor`, field order = declaration order, add the two new fields after the existing two.
  - `TranscodeResult.durationSeconds()` is now always populated.

- [ ] **Step 1: Write the failing guard tests**

Replace `TranscodeServiceImplTest.java`:

```java
package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MediaVideoProperties;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeServiceImplTest {

    private static final MinioProperties MINIO =
            new MinioProperties("http://localhost:9000", "key", "secret", "video-media");
    private static final MediaVideoProperties LIMITS =
            new MediaVideoProperties(524_288_000L, 600);

    @Mock private MinioClient minioClient;
    @Mock private VideoProbe videoProbe;

    private TranscodeServiceImpl service() {
        return new TranscodeServiceImpl(minioClient, MINIO, LIMITS, videoProbe);
    }

    private void stubStat(long size) throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(size);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
    }

    private void stubPresignedGet() throws Exception {
        lenient().when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/video-media/raw/7/vid123.mp4?sig=x");
    }

    @Test
    void transcode_withinLimits_copiesAndReturnsMeasuredDuration() throws Exception {
        stubStat(10_000_000L);
        stubPresignedGet();
        when(videoProbe.durationSeconds(any())).thenReturn(42);

        TranscodeResult result = service().transcode("vid123", "s3://video-media/raw/7/vid123.mp4");

        assertThat(result.hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/vid123/source.mp4");
        assertThat(result.durationSeconds()).isEqualTo(42);

        ArgumentCaptor<CopyObjectArgs> captor = ArgumentCaptor.forClass(CopyObjectArgs.class);
        verify(minioClient).copyObject(captor.capture());
        assertThat(captor.getValue().object()).isEqualTo("hls/vid123/source.mp4");
    }

    @Test
    void transcode_fileTooLarge_isRejectedWithoutCopying() throws Exception {
        stubStat(524_288_001L);

        assertThatThrownBy(() -> service().transcode("vid123", "s3://video-media/raw/7/vid123.mp4"))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining("500 MB");

        verify(minioClient, never()).copyObject(any());
    }

    @Test
    void transcode_tooLong_isRejectedWithoutCopying() throws Exception {
        stubStat(10_000_000L);
        stubPresignedGet();
        when(videoProbe.durationSeconds(any())).thenReturn(601);

        assertThatThrownBy(() -> service().transcode("vid123", "s3://video-media/raw/7/vid123.mp4"))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining("10m");

        verify(minioClient, never()).copyObject(any());
    }

    @Test
    void transcode_uploadNotInBucket_failsBeforeAnyMinioCall() {
        assertThatThrownBy(() -> service().transcode("vid123", "s3://other-bucket/raw/7/vid123.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run, verify it fails**

Run: `./mvnw test -pl services/media-worker -Dtest=TranscodeServiceImplTest`
Expected: compile failure — `MediaRejectedException`, `MediaVideoProperties`, the 4-arg constructor do not exist.

- [ ] **Step 3: Create `MediaRejectedException`**

```java
package com.tiktok.mediaworker.service;

/**
 * The uploaded file cannot be accepted and never will be — too large, too long, or unreadable.
 * Distinct from a transient storage error: VideoEventConsumer turns this into a single FAILED
 * result with no retry, where an IOException still gets the full retry budget.
 */
public class MediaRejectedException extends RuntimeException {
    public MediaRejectedException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Create `MediaVideoProperties`**

```java
package com.tiktok.mediaworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hard limits on an uploaded video. Same env-var names and defaults as video-service's
 * app.upload block, so the storage-edge limit and this backstop cannot drift.
 */
@ConfigurationProperties(prefix = "media.video")
public record MediaVideoProperties(
        long maxBytes,
        int maxDurationSeconds
) {
}
```

- [ ] **Step 5: Register the properties**

In `MediaWorkerApplication.java`:

```java
import com.tiktok.mediaworker.config.MediaVideoProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MediaVideoProperties.class)
public class MediaWorkerApplication {
```

- [ ] **Step 6: Add config to `application.yml`**

Under the existing `media:` key (sibling of `media.avatar`):

```yaml
media:
  video:
    # Backstop for the storage-edge limit video-service signs into its POST policy.
    # Same env vars as video-service's app.upload — keep them in step.
    max-bytes: ${VIDEO_MAX_BYTES:524288000}          # 500 MB
    max-duration-seconds: ${VIDEO_MAX_DURATION_SECONDS:600}  # 10 minutes
  avatar:
    # ...unchanged...
```

- [ ] **Step 7: Enforce the limits in `TranscodeServiceImpl`**

Replace the fields and `transcode` method:

```java
    private static final int PROBE_URL_EXPIRY_SECONDS = 300;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final MediaVideoProperties videoLimits;
    private final VideoProbe videoProbe;

    @Override
    @SneakyThrows
    public TranscodeResult transcode(String videoId, String rawFileUrl) {
        String bucket = minioProperties.bucket();
        String sourceKey = MediaKeys.objectKey(rawFileUrl, bucket).orElseThrow(() -> new IllegalArgumentException(
                "Raw upload %s of video %s is not in bucket %s".formatted(rawFileUrl, videoId, bucket)));
        String playbackKey = MediaKeys.playback(videoId);

        long sizeBytes = minioClient.statObject(StatObjectArgs.builder()
                .bucket(bucket).object(sourceKey).build()).size();
        if (sizeBytes > videoLimits.maxBytes()) {
            throw new MediaRejectedException("Video is %s; the maximum is %s."
                    .formatted(humanBytes(sizeBytes), humanBytes(videoLimits.maxBytes())));
        }

        String probeUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .object(sourceKey)
                .expiry(PROBE_URL_EXPIRY_SECONDS, TimeUnit.SECONDS)
                .build());
        int durationSeconds = videoProbe.durationSeconds(probeUrl);
        if (durationSeconds > videoLimits.maxDurationSeconds()) {
            throw new MediaRejectedException("Video is %s; the maximum is %s."
                    .formatted(humanDuration(durationSeconds), humanDuration(videoLimits.maxDurationSeconds())));
        }

        minioClient.copyObject(CopyObjectArgs.builder()
                .bucket(bucket)
                .object(playbackKey)
                .source(CopySource.builder().bucket(bucket).object(sourceKey).build())
                .build());

        log.info("Video {} is playable at {} ({}s)", videoId, playbackKey, durationSeconds);
        return new TranscodeResult(
                null, "%s/%s/%s".formatted(minioProperties.endpoint(), bucket, playbackKey), durationSeconds);
    }

    private static String humanBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1024 ? "%.2f GB".formatted(mb / 1024) : "%.0f MB".formatted(mb);
    }

    private static String humanDuration(int seconds) {
        return "%dm%02ds".formatted(seconds / 60, seconds % 60);
    }
```

Add imports: `io.minio.GetPresignedObjectUrlArgs`, `io.minio.StatObjectArgs`,
`io.minio.http.Method`, `java.util.concurrent.TimeUnit`. In the class Javadoc, drop the
"and no duration" half of the `ponytail:` paragraph; keep the "no thumbnail" half.

- [ ] **Step 8: Run the transcode test**

Run: `./mvnw test -pl services/media-worker -Dtest=TranscodeServiceImplTest`
Expected: PASS.

- [ ] **Step 9: Run the whole media-worker suite**

Run: `./mvnw test -pl services/media-worker`
Expected: PASS (`VideoEventConsumerTest` mocks `TranscodeService`, unaffected).

- [ ] **Step 10: Commit**

```bash
git add services/media-worker
git commit -m "$(cat <<'EOF'
feat(media-worker): reject oversize and over-long uploads

TranscodeServiceImpl stat-checks size and ffprobes duration (via a
short-lived presigned GET) before copying, throwing MediaRejectedException
past the 500 MB / 10 min limits. TranscodeResult now carries the measured
duration instead of null.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0118HWvjKsTH1ZffhbSSvDfu
EOF
)"
```

---

## Task 4: media-worker — no retry for a rejected file

**Files:**
- Modify: `services/media-worker/src/main/java/com/tiktok/mediaworker/event/consumer/VideoEventConsumer.java`
- Test: `services/media-worker/src/test/java/com/tiktok/mediaworker/event/consumer/VideoEventConsumerTest.java`

**Interfaces:**
- Consumes: `MediaRejectedException` from Task 3.
- Produces: unchanged public surface.

- [ ] **Step 1: Add the failing test**

In `VideoEventConsumerTest.java`, add:

```java
    @Test
    void onMessage_transcodeRejectsTheFile_failsOnceWithNoRetry() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of(
                "vid7", 1L, "Too long", "s3://raw/vid7.mp4", java.util.List.of());

        when(transcodeService.transcode("vid7", "s3://raw/vid7.mp4"))
                .thenThrow(new com.tiktok.mediaworker.service.MediaRejectedException(
                        "Video is 12m30s; the maximum is 10m00s."));

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        ArgumentCaptor<VideoTranscodedEvent> captor = ArgumentCaptor.forClass(VideoTranscodedEvent.class);
        verify(eventProducer).publish(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().failureReason()).isEqualTo("Video is 12m30s; the maximum is 10m00s.");
        verify(transcodeService, times(1)).transcode(anyString(), anyString());
    }
```

- [ ] **Step 2: Run it, verify it fails**

Run: `./mvnw test -pl services/media-worker -Dtest=VideoEventConsumerTest#onMessage_transcodeRejectsTheFile_failsOnceWithNoRetry`
Expected: FAIL — `transcode` called 3 times, not 1.

- [ ] **Step 3: Split the catch in `transcodeWithRetries`**

In `VideoEventConsumer.java`, change the loop body:

```java
        for (int attempt = 1; attempt <= transcodeAttempts; attempt++) {
            try {
                TranscodeResult result = transcodeService.transcode(event.videoId(), event.rawFileUrl());
                return VideoTranscodedEvent.success(
                        event.videoId(), result.thumbnailUrl(), result.hlsUrl(), result.durationSeconds());
            } catch (MediaRejectedException e) {
                // Permanently unacceptable — retrying re-probes the same file to the same answer.
                log.warn("Rejecting video {}: {}", event.videoId(), e.getMessage());
                return VideoTranscodedEvent.failure(event.videoId(), e.getMessage());
            } catch (Exception e) {
                lastFailure = e;
                log.warn("Transcode of video {} failed on attempt {}/{}: {}",
                        event.videoId(), attempt, transcodeAttempts, e.getMessage());
                if (attempt < transcodeAttempts) {
                    pause();
                }
            }
        }
```

Add `import com.tiktok.mediaworker.service.MediaRejectedException;`.

- [ ] **Step 4: Run the consumer suite**

Run: `./mvnw test -pl services/media-worker -Dtest=VideoEventConsumerTest`
Expected: PASS (new test + all existing).

- [ ] **Step 5: Commit**

```bash
git add services/media-worker
git commit -m "$(cat <<'EOF'
feat(media-worker): fail rejected uploads once, no retry

MediaRejectedException short-circuits transcodeWithRetries — an oversize or
over-long file becomes a single FAILED result with its reason, while
transient errors keep the full retry budget.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0118HWvjKsTH1ZffhbSSvDfu
EOF
)"
```

---

## Task 5: video-service — presigned POST policy for storage-edge size limit

**Files:**
- Create: `services/video-service/src/main/java/com/tiktok/videoservice/config/UploadLimitProperties.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/VideoServiceApplication.java`
- Modify: `services/video-service/src/main/resources/application.yml`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/dto/response/UploadUrlResponse.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `record UploadLimitProperties(long maxBytes, int maxDurationSeconds)`.
  - `UploadUrlResponse(String uploadUrl, Map<String,String> formFields, String fileUrl, long expiresInSeconds)`.
  - `VideoServiceImpl` constructor gains a trailing `UploadLimitProperties` parameter (declare the field last).
  - `createUploadUrl` returns `formFields` containing at least `key`, `Content-Type`, `policy`, plus MinIO's `x-amz-*`, and signs `content-length-range` `[1, maxBytes]`.

- [ ] **Step 1: Write the failing service test**

`grep -n "minioClient\|videoService =\|new VideoServiceImpl" services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java`
to see the file's existing mock wiring, then add a test consistent with it:

```java
    @Test
    void createUploadUrl_returnsAPostPolicyBoundedToTheSizeLimit() throws Exception {
        when(minioClient.getPresignedPostFormData(any(io.minio.PostPolicy.class)))
                .thenReturn(new java.util.HashMap<>(java.util.Map.of(
                        "policy", "base64policy",
                        "x-amz-signature", "deadbeef")));

        UploadUrlResponse response = videoService.createUploadUrl(7L, new UploadUrlRequest("video/mp4"));

        assertThat(response.uploadUrl()).isEqualTo("http://localhost:9000/video-media");
        assertThat(response.formFields())
                .containsKeys("policy", "x-amz-signature", "key", "Content-Type");
        assertThat(response.formFields().get("Content-Type")).isEqualTo("video/mp4");
        assertThat(response.formFields().get("key")).matches("raw/7/\\d+\\.mp4");
        assertThat(response.fileUrl()).matches("s3://video-media/raw/7/\\d+\\.mp4");
        verify(minioClient, never()).getPresignedObjectUrl(any());
    }
```

If `VideoServiceImplTest` has no `minioClient` mock / `videoService` upload wiring,
add them following `VideoServiceImplUploadUrlFailureTest`'s construction pattern
(including the new `UploadLimitProperties` arg from Step 6).

- [ ] **Step 2: Run it, verify it fails**

Run: `./mvnw test -pl services/video-service -Dtest=VideoServiceImplTest#createUploadUrl_returnsAPostPolicyBoundedToTheSizeLimit`
Expected: compile failure (`formFields()` / `getPresignedPostFormData` / ctor arity).

- [ ] **Step 3: Create `UploadLimitProperties`**

```java
package com.tiktok.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload ceilings. {@code maxBytes} is signed into the presigned POST policy so MinIO rejects
 * an oversize file itself; {@code maxDurationSeconds} is not enforced here (this service never
 * sees the bytes) — media-worker is the backstop for both. Same env vars as media-worker's
 * media.video block.
 */
@ConfigurationProperties(prefix = "app.upload")
public record UploadLimitProperties(
        long maxBytes,
        int maxDurationSeconds
) {
}
```

- [ ] **Step 4: Register it and add config**

`VideoServiceApplication.java`:

```java
import com.tiktok.videoservice.config.UploadLimitProperties;
// ...
@EnableConfigurationProperties({MediaUrlProperties.class, VideoCacheProperties.class, UploadLimitProperties.class})
```

`application.yml`, under the existing `app:` key (sibling of `app.cache`):

```yaml
app:
  cache:
    # ...unchanged...
  upload:
    # max-bytes is signed into the upload POST policy; media-worker enforces both as a backstop.
    # Same env vars there — keep in step.
    max-bytes: ${VIDEO_MAX_BYTES:524288000}          # 500 MB
    max-duration-seconds: ${VIDEO_MAX_DURATION_SECONDS:600}  # 10 minutes
```

- [ ] **Step 5: Change `UploadUrlResponse`**

```java
package com.tiktok.videoservice.dto.response;

import java.util.Map;

/**
 * @param uploadUrl        the bucket URL to POST the multipart form to — the bytes never pass
 *                         through this service
 * @param formFields       every field the multipart POST must carry (policy, signature, key,
 *                         Content-Type). The client appends these, then the file last.
 * @param fileUrl          what to send back as {@code rawFileUrl} on POST /api/v1/videos
 * @param expiresInSeconds how long the policy stays valid
 */
public record UploadUrlResponse(
        String uploadUrl,
        Map<String, String> formFields,
        String fileUrl,
        long expiresInSeconds
) {
}
```

- [ ] **Step 6: Rewrite `createUploadUrl`**

In `VideoServiceImpl.java` add the field (declared last among `private final` fields):

```java
    private final UploadLimitProperties uploadLimits;
```

Replace the method, and delete the obsolete `ponytail:` paragraph in its Javadoc:

```java
    @Override
    public UploadUrlResponse createUploadUrl(Long userId, UploadUrlRequest request) {
        String contentType = mediaType(request.contentType());
        String extension = UPLOAD_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new UnsupportedUploadTypeException(request.contentType(), UPLOAD_EXTENSIONS.keySet());
        }

        String objectKey = "%s/%d/%s.%s".formatted(UPLOAD_PREFIX, userId, Video.newId(), extension);
        long expirySeconds = minioProperties.urlExpiry().toSeconds();

        PostPolicy policy = new PostPolicy(minioProperties.bucket(),
                ZonedDateTime.now().plusSeconds(expirySeconds));
        policy.addEqualsCondition("key", objectKey);
        policy.addEqualsCondition("Content-Type", contentType);
        policy.addContentLengthRangeCondition(1, uploadLimits.maxBytes());

        Map<String, String> formFields;
        try {
            formFields = new HashMap<>(minioClient.getPresignedPostFormData(policy));
        } catch (Exception e) {
            log.error("Failed to presign an upload POST for bucket {}", minioProperties.bucket(), e);
            throw new UploadUrlUnavailableException(e);
        }
        formFields.put("key", objectKey);
        formFields.put("Content-Type", contentType);

        String uploadUrl = "%s/%s".formatted(minioProperties.endpoint(), minioProperties.bucket());
        String fileUrl = "s3://%s/%s".formatted(minioProperties.bucket(), objectKey);
        return new UploadUrlResponse(uploadUrl, formFields, fileUrl, expirySeconds);
    }
```

Imports to add: `io.minio.PostPolicy`, `java.time.ZonedDateTime`, `java.util.HashMap`,
`java.util.Map`. `grep` the file first — remove `GetPresignedObjectUrlArgs`,
`io.minio.http.Method`, `java.util.concurrent.TimeUnit` imports only if nothing else uses them.
`mediaType(...)` is the existing private helper that strips `;charset=…`.

- [ ] **Step 7: Fix the two constructor call sites in tests**

`VideoServiceImplUploadUrlFailureTest.java` — throw from the POST presign, add the arg:

```java
        when(minioClient.getPresignedPostFormData(any(io.minio.PostPolicy.class)))
                .thenThrow(new io.minio.errors.ServerException("storage said no", 500, null));

        VideoService videoService = new VideoServiceImpl(
                mock(VideoRepository.class),
                mock(VideoMapper.class),
                new SpringDataWebProperties(),
                minioClient,
                new MinioProperties("http://localhost:9000", "key", "secret",
                        "video-media", "us-east-1", Duration.ofMinutes(15)),
                mock(VideoCache.class),
                mock(com.tiktok.videoservice.client.FriendshipClient.class),
                new com.tiktok.videoservice.config.UploadLimitProperties(524_288_000L, 600));
```

Drop the now-unused `GetPresignedObjectUrlArgs` import. Keep the assertions
(`UploadUrlUnavailableException`, no `localhost` / `video-media` in the message).

`VideoServiceImplCacheTest.java` — append `new UploadLimitProperties(524_288_000L, 600)`
to its `new VideoServiceImpl(...)` call, matching the real constructor's parameter order
(add the import).

- [ ] **Step 8: Run the video-service suite**

Run: `./mvnw test -pl services/video-service`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add services/video-service
git commit -m "$(cat <<'EOF'
feat(video-service): presigned POST upload with a size limit

createUploadUrl now signs a MinIO POST policy carrying
content-length-range [1, 500MB] instead of an unbounded presigned PUT, so
storage rejects an oversize file before storing any bytes. UploadUrlResponse
now returns the form fields the multipart POST must carry.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0118HWvjKsTH1ZffhbSSvDfu
EOF
)"
```

---

## Task 6: frontend — adapt the upload call to the POST contract

**Files:**
- Modify: `tiktok-cloned/src/lib/api/types.ts`
- Modify: `tiktok-cloned/src/lib/api/videos.ts`

**Interfaces:**
- Consumes: `UploadUrlResponse` shape from Task 5 (`uploadUrl`, `formFields`, `fileUrl`, `expiresInSeconds`).
- Produces: `uploadToStorage(uploadUrl, file, formFields, onProgress?, signal?)` — new required `formFields` param, inserted before `onProgress`.

- [ ] **Step 1: Update the types**

In `types.ts`, replace `UploadUrlResponse`:

```ts
export interface UploadUrlResponse {
  /** The bucket URL to POST the multipart form to. Expires — upload straight away. */
  uploadUrl: string;
  /** Every field the multipart POST must carry. Append these, then the file last. */
  formFields: Record<string, string>;
  /** The `s3://` location to send back as `rawFileUrl` once the POST succeeds. */
  fileUrl: string;
  expiresInSeconds: number;
}
```

Find the `VideoResponse` interface in the same file and add:

```ts
  /** Why the transcode failed; present only when `status === "FAILED"`. */
  failureReason?: string | null;
```

- [ ] **Step 2: Rewrite `uploadToStorage` in `videos.ts`**

```ts
/**
 * POSTs the file to object storage as a multipart form: the presigned policy fields
 * first, then the file **last** (S3 requires that order). XHR rather than `fetch` so
 * upload progress can drive the bar. Storage answers 2xx with an empty body.
 */
export function uploadToStorage(
  uploadUrl: string,
  file: File,
  formFields: Record<string, string>,
  onProgress?: (fraction: number) => void,
  signal?: AbortSignal,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const body = new FormData();
    for (const [name, value] of Object.entries(formFields)) body.append(name, value);
    body.append("file", file); // must be last

    const xhr = new XMLHttpRequest();
    xhr.open("POST", uploadUrl);

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(event.loaded / event.total);
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress?.(1);
        resolve();
        return;
      }
      reject(new ApiError(xhr.status, "UPLOAD_FAILED", "Upload failed"));
    };
    xhr.onerror = () =>
      reject(new ApiError(0, "NETWORK_ERROR", "Cannot reach the storage host"));
    xhr.onabort = () => reject(new DOMException("Aborted", "AbortError"));

    if (signal?.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    signal?.addEventListener("abort", () => xhr.abort(), { once: true });
    xhr.send(body);
  });
}
```

- [ ] **Step 3: Typecheck (call site still broken — expected)**

Run: `cd ../tiktok-cloned && npm run typecheck`
Expected: one error in `UploadPage.tsx` — `uploadToStorage` now wants `formFields`. Task 7 fixes it.

- [ ] **Step 4: Commit**

```bash
cd ../tiktok-cloned
git add src/lib/api/types.ts src/lib/api/videos.ts
git commit -m "feat(upload): POST multipart to storage with the presigned policy fields"
cd -
```

---

## Task 7: frontend — client-side size & duration pre-check + failure reason

**Files:**
- Modify: `tiktok-cloned/src/components/upload/UploadPage.tsx`

**Interfaces:**
- Consumes: `uploadToStorage(uploadUrl, file, formFields, onProgress?, signal?)` from Task 6; `VideoResponse.failureReason` from Task 5/6.
- Produces: nothing downstream.

- [ ] **Step 1: Replace the size constant, add a duration one**

Replace `const MAX_BYTES = 2 * 1024 * 1024 * 1024;` with:

```ts
const MAX_BYTES = 500 * 1024 * 1024;
const MAX_DURATION_SECONDS = 600;
```

- [ ] **Step 2: Add a `readDuration` helper (near `formatBytes`)**

```ts
/** Reads a video file's length via a detached media element. Rejects if it has none. */
function readDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const probe = document.createElement("video");
    probe.preload = "metadata";
    probe.onloadedmetadata = () => {
      URL.revokeObjectURL(url);
      const seconds = probe.duration;
      Number.isFinite(seconds) ? resolve(seconds) : reject(new Error("no-duration"));
    };
    probe.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("not-media"));
    };
    probe.src = url;
  });
}
```

- [ ] **Step 3: Make `chooseFile` async and add the checks**

```ts
  async function chooseFile(next: File | null) {
    if (!next) return;
    if (!ACCEPTED_UPLOAD_TYPES.includes(next.type as never)) {
      setFileError("That file isn’t a supported video. Use MP4, MOV or WebM.");
      return;
    }
    if (next.size > MAX_BYTES) {
      setFileError("That file is over 500 MB.");
      return;
    }

    let seconds: number;
    try {
      seconds = await readDuration(next);
    } catch {
      setFileError("That file isn’t a video we can read.");
      return;
    }
    if (seconds > MAX_DURATION_SECONDS) {
      setFileError("That video is longer than 10 minutes.");
      return;
    }

    setFileError(null);
    setFile(next);
    setPreviewUrl(URL.createObjectURL(next));
    if (!form.values.title) {
      form.setValue("title", next.name.replace(/\.[^.]+$/, "").slice(0, 150));
    }
  }
```

If the linter flags floating promises at the call sites, wrap them:
`onFile={(f) => void chooseFile(f)}` in `DropZone`, `onDrop`, and the `Preview` replace input.

- [ ] **Step 4: Pass `formFields` to `uploadToStorage` in `onSubmit`**

```ts
        const target = await createUploadUrl({ contentType: file.type });
        await uploadToStorage(target.uploadUrl, file, target.formFields, setProgress, signal);
```

- [ ] **Step 5: Show the failure reason in the FAILED branch**

```ts
        if (latest.status === "FAILED") {
          const message =
            latest.failureReason ??
            "Transcoding failed. Try uploading the file again.";
          setFormError(message);
          toast.error(message);
        } else {
```

- [ ] **Step 6: Typecheck + lint**

Run: `cd ../tiktok-cloned && npm run typecheck && npm run lint`
Expected: PASS.

- [ ] **Step 7: Manual smoke (if infra is up)**

With infra + video-service + media-worker + gateway running and `npm run dev`:
- >10-minute clip → "That video is longer than 10 minutes.", no upload starts.
- >500 MB file → "That file is over 500 MB."
- short small clip → uploads, posts, transcodes, plays.

- [ ] **Step 8: Commit**

```bash
cd ../tiktok-cloned
git add src/components/upload/UploadPage.tsx
git commit -m "feat(upload): pre-check size and duration, show transcode failure reason"
cd -
```

---

## Task 8: frontend — character counter + corrected limit copy

**Files:**
- Modify: `tiktok-cloned/src/components/upload/UploadPage.tsx`

**Interfaces:** none in/out.

- [ ] **Step 1: Add the counter to `TextField`**

Replace the `return (` block of `TextField`:

```tsx
  const atLimit = maxLength !== undefined && value.length >= maxLength;
  const counterId = maxLength !== undefined ? `${label}-count` : undefined;

  return (
    <Labelled label={label} error={error}>
      {multiline ? (
        <textarea
          {...shared}
          aria-describedby={counterId}
          className={`${className} h-24 resize-none py-2`}
        />
      ) : (
        <input {...shared} aria-describedby={counterId} className={className} />
      )}
      {maxLength !== undefined && (
        <p
          id={counterId}
          className={`mt-1 text-right text-[13px] leading-[18px] ${
            atLimit
              ? "text-[var(--tt-red-active)]"
              : "text-[var(--tt-text-secondary)]"
          }`}
        >
          {value.length} / {maxLength}
        </p>
      )}
    </Labelled>
  );
```

(`shared` already spreads `maxLength` onto the element, so input stays capped and the
counter can't exceed the max.)

- [ ] **Step 2: Fix the limits copy in `DropZone`**

Replace the first `<Fact>`:

```tsx
        <Fact term="Size and duration">
          Up to 500 MB and 10 minutes.
        </Fact>
```

- [ ] **Step 3: Typecheck + lint + build**

Run: `cd ../tiktok-cloned && npm run check`
Expected: PASS.

- [ ] **Step 4: Manual check**

`npm run dev` → `/upload`: Title and Description show `N / 150` and `N / 2000`, the
number turns red at the cap, drop-zone facts read "Up to 500 MB and 10 minutes."

- [ ] **Step 5: Commit**

```bash
cd ../tiktok-cloned
git add src/components/upload/UploadPage.tsx
git commit -m "feat(upload): live char counter for title/description, correct limit copy"
cd -
```

---

## Task 9: docs + Postman

**Files:**
- Modify: `docs/video-service-api.md`
- Modify: `postman/` collection (the `.json` holding the `upload-url` request)

**Interfaces:** none.

- [ ] **Step 1: Update `docs/video-service-api.md`**

In the `POST /api/v1/videos/upload-url` section:
- Response body is now `{ uploadUrl, formFields, fileUrl, expiresInSeconds }`.
- The client builds `multipart/form-data`: every `formFields` entry, then a `file` part
  last, POSTed to `uploadUrl`.
- Storage rejects a file larger than 500 MB with HTTP 400 (`EntityTooLarge`).
- In the publish/poll flow, note a video can return `FAILED` with a `failureReason`
  string such as `"Video is 12m30s; the maximum is 10m00s."`.

- [ ] **Step 2: Update the Postman collection**

`grep -rl "upload-url" postman/` → in that file, update the saved example response to
the new shape. Leave the raw S3 request example with a note that the browser flow is a
multipart POST.

- [ ] **Step 3: Env-var template**

`grep -rl "VIDEO_CACHE_ENABLED" .` — if a `.env.example` / deployment env template
turns up, add `VIDEO_MAX_BYTES=524288000` and `VIDEO_MAX_DURATION_SECONDS=600`.

- [ ] **Step 4: Commit**

```bash
git add docs/video-service-api.md postman
git commit -m "$(cat <<'EOF'
docs: document upload POST policy, size/duration limits, failureReason

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0118HWvjKsTH1ZffhbSSvDfu
EOF
)"
```

---

## Final verification

- [ ] `./mvnw test -pl services/video-service,services/media-worker` — all green.
- [ ] `cd ../tiktok-cloned && npm run check` — green.
- [ ] `grep -rn "DEV ONLY" services/` — still empty (sanity, unchanged).
- [ ] Manual end-to-end with infra up: a normal short clip uploads/posts/plays; a
      >500 MB file is refused by the browser pre-check and by MinIO if forced past it;
      a >10 min clip is refused by the pre-check and comes back `FAILED` with the
      duration message on the upload screen if forced past it.

---

## Self-Review

**Spec coverage:**
- §4.1 media-worker size+duration → Tasks 2, 3, 4.
- §4.2 failureReason plumbing → Task 1.
- §4.3 POST policy + `UploadUrlResponse` → Task 5.
- §4.4 frontend contract → Task 6.
- §4.5 frontend pre-check + FAILED reason → Task 7.
- §4.6 counter + copy → Task 8.
- §5 testing → each task's Steps; frontend has no runner (Global Constraints), verified via typecheck/lint/build + manual.
- §6 rollout/docs → Task 9.
No spec requirement is left without a task.

**Type consistency:**
- `markFailed(String)` — Task 1 Step 3 defines, Step 6 uses. `updateFailed(Video, VideoStatus)` — Step 4 defines, Steps 5–6 use.
- `VideoProbe.durationSeconds(String)` — Task 2 Step 5 defines, Task 3 Steps 1/7 use.
- `MediaRejectedException(String)` — Task 3 Step 3 defines, Task 3 Step 7 + Task 4 use.
- `MediaVideoProperties(long, int)` / `UploadLimitProperties(long, int)` — same shape, one per service, intentional per spec §3.
- `UploadUrlResponse(uploadUrl, formFields, fileUrl, expiresInSeconds)` — Task 5 Step 5 defines; Task 6 Step 1 mirrors the TS type; Task 7 Step 4 consumes `formFields`.
- `uploadToStorage(uploadUrl, file, formFields, onProgress?, signal?)` — Task 6 Step 2 defines; Task 7 Step 4 calls with that exact arg order.

**Placeholder scan:** no TBD/TODO; every code step carries real code. Fixture-mp4 creation (Task 2 Step 2) gives an exact command plus a fallback.
