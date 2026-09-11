# Realtime Video Stats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Push video counters, state changes and new comments to `tiktok-cloned` over WebSocket so the UI stops needing a manual reload, and stop a like/unlike burst from turning into a burst of events, frames and (later) notifications.

**Architecture:** `chat-service` becomes the realtime hub — it already owns the STOMP endpoint `/ws`, the JWT handshake and the gateway route. It consumes the interaction and video Kafka topics, coalesces counter churn into one frame per video per 500ms, and publishes to `/topic/videos.{videoId}`. `interaction-service` gains a public `save_count`, a save event, a batch counts endpoint and a like rate-limit bucket. The frontend subscribes per rendered video and debounces the like button.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Spring Kafka (via `kafka-lib`), Spring WebSocket + STOMP, Cassandra counter columns, Redis, JUnit 5 + AssertJ + Testcontainers, Mockito. Frontend: Next.js, `@stomp/stompjs`.

**Spec:** `docs/superpowers/specs/2026-09-09-realtime-video-stats-design.md`

## Global Constraints

- Constructor injection only, via `@RequiredArgsConstructor`. Never `@Autowired` on a field.
- DTOs are Java `record`s.
- Custom exceptions extend `com.tiktok.common.exception.DomainException`; `GlobalExceptionHandler` already turns them into `ApiResponse`.
- Never query another service's database. interaction-service data is reached over HTTP.
- Every Kafka consumer must be idempotent, and consumers on a mixed topic route on the `eventType` **header**, never on JSON shape. A missing header on `video.video-events` means `VideoPublishedEvent`.
- Video ids are Snowflake. video-service stores them as `String` (`Video.newId()` returns `String.valueOf(SnowflakeIdGenerator.nextId())`), interaction-service uses `Long`. The decimal text form is identical in both, and that text form is what goes in STOMP destinations and in JSON sent to the browser — `JSON.parse` corrupts a bare 64-bit number.
- Redis and cache failures fail open: they cost latency, never correctness.
- Run tests with `./mvnw test -pl services/<name>`. interaction-service tests are Testcontainers integration tests extending `AbstractInteractionServiceIT`.

---

### Task 1: Public `save_count` on video_counters

**Files:**
- Modify: `scripts/cassandra-init.cql:92-102`
- Modify: `services/interaction-service/src/test/resources/cassandra-init-test.cql:78-88`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/entity/VideoCounters.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/repository/VideoCountersRepository.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/VideoCounts.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/CounterCacheServiceImpl.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/SaveServiceImpl.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/dto/response/InteractionCountResponse.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/controller/ShareController.java:33-38`
- Test: `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/SaveServiceImplTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `VideoCounts(long likeCount, long commentCount, long shareCount, long viewCount, long saveCount)`; `VideoCountersRepository.incrementSaveCount(Long videoId, long delta)`; `InteractionCountResponse(Long videoId, long likeCount, long commentCount, long shareCount, long viewCount, long saveCount)`.

- [ ] **Step 1: Add the counter column to both schema files**

In `scripts/cassandra-init.cql`, replace the `video_counters` block (lines 92-102) with:

```sql
CREATE TABLE IF NOT EXISTS video_counters (
    video_id      bigint PRIMARY KEY,
    like_count    counter,
    comment_count counter,
    share_count   counter,
    view_count    counter,
    save_count    counter
);

-- For clusters created before view_count existed: CREATE TABLE IF NOT EXISTS above is a no-op
-- on an existing table and would leave the column missing.
ALTER TABLE video_counters ADD IF NOT EXISTS view_count counter;
-- Same reason, for clusters created before saves were counted publicly.
ALTER TABLE video_counters ADD IF NOT EXISTS save_count counter;
```

Apply the identical change to `services/interaction-service/src/test/resources/cassandra-init-test.cql` (lines 78-88).

- [ ] **Step 2: Write the failing test**

Append to `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/SaveServiceImplTest.java` — `CounterCacheService` is in the same package, so no import is needed for it:

```java
    @Autowired
    private CounterCacheService counterCacheService;

    @Test
    void save_movesTheSaveCounter() {
        saveService.save(30L, 1L);

        assertThat(counterCacheService.getCounts(30L).saveCount()).isEqualTo(1);
    }

    @Test
    void save_calledTwiceBySameUser_movesTheCounterOnce() {
        saveService.save(31L, 1L);
        saveService.save(31L, 1L);

        assertThat(counterCacheService.getCounts(31L).saveCount()).isEqualTo(1);
    }

    @Test
    void unsave_movesTheSaveCounterBack() {
        saveService.save(32L, 1L);
        saveService.unsave(32L, 1L);

        assertThat(counterCacheService.getCounts(32L).saveCount()).isZero();
    }

    @Test
    void unsave_ofAVideoNeverSaved_leavesTheCounterAlone() {
        saveService.unsave(33L, 1L);

        assertThat(counterCacheService.getCounts(33L).saveCount()).isZero();
    }
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -pl services/interaction-service -Dtest=SaveServiceImplTest`
Expected: FAIL — compile error, `VideoCounts` has no `saveCount()`.

- [ ] **Step 4: Add the entity column**

In `VideoCounters.java`, after the `view_count` field:

```java
    @Column("save_count")
    private Long saveCount;
```

- [ ] **Step 5: Add the repository increment**

In `VideoCountersRepository.java`, after `incrementViewCount`:

```java
    @Query("UPDATE video_counters SET save_count = save_count + :delta WHERE video_id = :videoId")
    void incrementSaveCount(@Param("videoId") Long videoId, @Param("delta") long delta);
```

- [ ] **Step 6: Widen `VideoCounts`**

Replace the whole of `VideoCounts.java`:

```java
package com.tiktok.interactionservice.service;

public record VideoCounts(
        long likeCount,
        long commentCount,
        long shareCount,
        long viewCount,
        long saveCount
) {
    public static final VideoCounts ZERO = new VideoCounts(0, 0, 0, 0, 0);
}
```

- [ ] **Step 7: Widen the cache serialization**

In `CounterCacheServiceImpl.java`:

Change the field count constant — the class comment already explains that a mismatched field count is treated as a miss, which is exactly how keys written before this deploy heal themselves:

```java
    private static final int FIELDS = 5;
```

In `readCache`, extend the parse:

```java
            return new VideoCounts(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]),
                    Long.parseLong(parts[4]));
```

In `writeCache`:

```java
            redisTemplate.opsForValue().set(key, "%d%s%d%s%d%s%d%s%d".formatted(
                    counts.likeCount(), SEPARATOR,
                    counts.commentCount(), SEPARATOR,
                    counts.shareCount(), SEPARATOR,
                    counts.viewCount(), SEPARATOR,
                    counts.saveCount()), TTL);
```

In `toVideoCounts`:

```java
        return new VideoCounts(
                orZero(counters.getLikeCount()),
                orZero(counters.getCommentCount()),
                orZero(counters.getShareCount()),
                orZero(counters.getViewCount()),
                orZero(counters.getSaveCount()));
```

- [ ] **Step 8: Move the counter in `SaveServiceImpl`**

Add the import:

```java
import com.tiktok.interactionservice.repository.VideoCountersRepository;
```

Add two fields beside the existing constructor-injected ones:

```java
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
```

Replace the whole `if (newlySaved)` block in `save` with:

```java
        if (newlySaved) {
            // The LWT is what grants the right to move the counter, and it grants it exactly
            // once. A failure past this point has to give the claim back, or the save stays
            // stored against a counter that is short by one for good — the same compensation
            // LikeServiceImpl does around its claim.
            boolean countered = false;
            try {
                videoCountersRepository.incrementSaveCount(videoId, 1);
                countered = true;
                saveByUserTimeRepository.save(SaveByUserTime.builder()
                        .key(SaveByUserTimeKey.builder()
                                .userId(currentUserId)
                                .createdAt(savedAt)
                                .videoId(videoId)
                                .build())
                        .build());
                counterCacheService.invalidate(videoId);
            } catch (RuntimeException ex) {
                // Counter first, then the claim, each only if it actually landed. Giving the
                // claim back alone would leave the increment behind, so the client's retry takes
                // a fresh claim and adds a second one for the same save.
                if (countered) {
                    undo(() -> videoCountersRepository.incrementSaveCount(videoId, -1),
                            videoId, currentUserId, ex);
                }
                undo(() -> saveByUserRepository.deleteIfExists(currentUserId, videoId),
                        videoId, currentUserId, ex);
                throw ex;
            }
        }
```

Replace the whole `if (wasSaved)` block in `unsave` with:

```java
        if (wasSaved) {
            boolean countered = false;
            try {
                videoCountersRepository.incrementSaveCount(videoId, -1);
                countered = true;
                saveByUserTimeRepository.deleteById(SaveByUserTimeKey.builder()
                        .userId(currentUserId)
                        .createdAt(savedAt)
                        .videoId(videoId)
                        .build());
                counterCacheService.invalidate(videoId);
            } catch (RuntimeException ex) {
                if (countered) {
                    undo(() -> videoCountersRepository.incrementSaveCount(videoId, 1),
                            videoId, currentUserId, ex);
                }
                // The original timestamp, not a fresh one: the restored claim has to keep
                // addressing the listing row.
                undo(() -> saveByUserRepository.insertIfNotExists(currentUserId, videoId, savedAt),
                        videoId, currentUserId, ex);
                throw ex;
            }
        }
```

- [ ] **Step 9: Widen the response DTO and its one caller**

Replace `InteractionCountResponse.java`:

```java
package com.tiktok.interactionservice.dto.response;

public record InteractionCountResponse(
        Long videoId,
        long likeCount,
        long commentCount,
        long shareCount,
        long viewCount,
        long saveCount
) {
}
```

In `ShareController.counts` (lines 33-38):

```java
    @GetMapping("/counts")
    public ApiResponse<InteractionCountResponse> counts(@PathVariable Long videoId) {
        VideoCounts counts = counterCacheService.getCounts(videoId);
        return ApiResponse.success(new InteractionCountResponse(
                videoId, counts.likeCount(), counts.commentCount(), counts.shareCount(),
                counts.viewCount(), counts.saveCount()));
    }
```

- [ ] **Step 10: Fix every other `VideoCounts` construction the compiler finds**

Run: `./mvnw -q compile -pl services/interaction-service -am`
Fix each reported call site by appending the new argument. `CounterCacheServiceImplTest` constructs `VideoCounts` directly and will need the same.

- [ ] **Step 11: Run the tests**

Run: `./mvnw test -pl services/interaction-service`
Expected: PASS, including the four new `SaveServiceImplTest` cases.

- [ ] **Step 12: Commit**

```bash
git add scripts/cassandra-init.cql services/interaction-service
git commit -m "feat(interaction-service): count saves publicly on video_counters"
```

---

### Task 2: `VideoSavedEvent` on `interaction.save-events`

**Files:**
- Create: `libs/event-schema/src/main/java/com/tiktok/event/interaction/VideoSavedEvent.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/event/producer/InteractionEventPublisher.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/SaveServiceImpl.java`
- Test: `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/SaveServiceImplTest.java`

**Interfaces:**
- Consumes: `SaveServiceImpl` from Task 1, with its compensation blocks.
- Produces: `VideoSavedEvent.of(Long videoId, Long userId, boolean saved)` with fields `(String eventId, Instant occurredAt, Long videoId, Long userId, boolean saved)`; `InteractionEventPublisher.publishSave(Long videoId, Long userId, boolean saved)`; topic name `interaction.save-events`.

- [ ] **Step 1: Create the event**

`libs/event-schema/src/main/java/com/tiktok/event/interaction/VideoSavedEvent.java`:

```java
package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A viewer added a video to their favourites, or took it back out. Same shape as
 * {@link VideoLikeEvent} and keyed by video for the same reason: one video's saves stay ordered
 * within a partition, so a save and the unsave that follows it cannot be applied out of order.
 */
public record VideoSavedEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long userId,
        boolean saved
) implements DomainEvent {

    public static VideoSavedEvent of(Long videoId, Long userId, boolean saved) {
        return new VideoSavedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, userId, saved);
    }
}
```

- [ ] **Step 2: Add the publisher method**

In `InteractionEventPublisher.java`, add the import:

```java
import com.tiktok.event.interaction.VideoSavedEvent;
```

Add the topic constant beside the others:

```java
    private static final String SAVE_TOPIC = "interaction.save-events";
```

Add the method after `publishShare`:

```java
    /** Confirmed like the rest, because it moves save_count. */
    @SneakyThrows
    public void publishSave(Long videoId, Long userId, boolean saved) {
        VideoSavedEvent event = VideoSavedEvent.of(videoId, userId, saved);
        confirm(new ProducerRecord<>(
                SAVE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event)));
    }
```

- [ ] **Step 3: Write the failing test**

Append to `SaveServiceImplTest.java`. Add imports:

```java
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
```

Add the spy field and the tests:

```java
    @MockitoSpyBean
    private InteractionEventPublisher eventPublisher;

    @Test
    void save_publishesASaveEvent() {
        saveService.save(40L, 1L);

        verify(eventPublisher).publishSave(40L, 1L, true);
    }

    @Test
    void save_calledTwiceBySameUser_publishesOnce() {
        saveService.save(41L, 1L);
        saveService.save(41L, 1L);

        verify(eventPublisher).publishSave(41L, 1L, true);
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void unsave_publishesAnUnsaveEvent() {
        saveService.save(42L, 1L);
        saveService.unsave(42L, 1L);

        verify(eventPublisher).publishSave(42L, 1L, false);
    }
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw test -pl services/interaction-service -Dtest=SaveServiceImplTest`
Expected: FAIL — `Wanted but not invoked: publishSave`.

- [ ] **Step 5: Publish from `SaveServiceImpl`**

Add the import:

```java
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
```

Add the field:

```java
    private final InteractionEventPublisher eventPublisher;
```

In `save`, inside the `try` block, immediately after `counterCacheService.invalidate(videoId);`:

```java
                eventPublisher.publishSave(videoId, currentUserId, true);
```

In `unsave`, in the same position:

```java
                eventPublisher.publishSave(videoId, currentUserId, false);
```

The publish sits **inside** the compensated block on purpose: a broker that refuses the record must roll the counter and the claim back, or the counter has moved and nothing downstream will ever hear about it.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test -pl services/interaction-service`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add libs/event-schema services/interaction-service
git commit -m "feat(interaction-service): publish VideoSavedEvent on save and unsave"
```

---

### Task 3: Batch counts endpoint

**Files:**
- Create: `services/interaction-service/src/main/java/com/tiktok/interactionservice/controller/VideoCountsController.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/config/SecurityConfig.java:35`
- Test: `services/interaction-service/src/test/java/com/tiktok/interactionservice/controller/VideoCountsControllerTest.java`

**Interfaces:**
- Consumes: `CounterCacheService.getCounts(Long)`, `InteractionCountResponse` with `saveCount` from Task 1.
- Produces: `GET /api/v1/interactions/videos/counts/batch?videoIds=1,2,3` returning `ApiResponse<List<InteractionCountResponse>>`, permitAll, capped at 50 distinct ids.

A separate controller because `ShareController` is mapped at `/api/v1/interactions/videos/{videoId}`, where a literal `counts/batch` segment would be swallowed by the path variable.

- [ ] **Step 1: Write the failing test**

Create `services/interaction-service/src/test/java/com/tiktok/interactionservice/controller/VideoCountsControllerTest.java`:

```java
package com.tiktok.interactionservice.controller;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.service.LikeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VideoCountsControllerTest extends AbstractInteractionServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LikeService likeService;

    @Test
    void batch_returnsOneRowPerDistinctId_withoutAuthentication() throws Exception {
        likeService.like(50L, 1L);
        likeService.like(50L, 2L);
        likeService.like(51L, 1L);

        mockMvc.perform(get("/api/v1/interactions/videos/counts/batch")
                        .param("videoIds", "50,51,50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].videoId").value(50))
                .andExpect(jsonPath("$.data[0].likeCount").value(2))
                .andExpect(jsonPath("$.data[1].videoId").value(51))
                .andExpect(jsonPath("$.data[1].likeCount").value(1));
    }

    @Test
    void batch_capsTheNumberOfIdsItWillRead() throws Exception {
        String ids = IntStream.rangeClosed(1, 60)
                .mapToObj(i -> String.valueOf(1000 + i))
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/api/v1/interactions/videos/counts/batch").param("videoIds", ids))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(50));
    }
}
```

If `AbstractInteractionServiceIT` already registers `MockMvc`, drop the `@AutoConfigureMockMvc` annotation and its import — check the base class before running.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -pl services/interaction-service -Dtest=VideoCountsControllerTest`
Expected: FAIL — 404, no handler for `/api/v1/interactions/videos/counts/batch`.

- [ ] **Step 3: Create the controller**

`services/interaction-service/src/main/java/com/tiktok/interactionservice/controller/VideoCountsController.java`:

```java
package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.InteractionCountResponse;
import com.tiktok.interactionservice.service.CounterCacheService;
import com.tiktok.interactionservice.service.VideoCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Counters for many videos in one request. It exists for the realtime fan-out in chat-service,
 * which learns from Kafka that a set of videos moved and then needs their current numbers — one
 * request per video would be one HTTP round trip per like.
 *
 * <p>Its own controller rather than a method on ShareController: that one is mapped under
 * {@code /videos/{videoId}}, where a literal {@code counts} segment would be swallowed by the
 * path variable.
 */
@RestController
@RequestMapping("/api/v1/interactions/videos")
@RequiredArgsConstructor
public class VideoCountsController {

    /**
     * Same ceiling as the like-status batch, and here for the same reason: every id costs a cache
     * read and possibly a Cassandra point read, and the ids arrive in a query string where
     * nothing else limits how many a caller may ask for. Distinct first, so a list padded with
     * one repeated id cannot use up the cap.
     */
    private static final int MAX_BATCH_SIZE = 50;

    private final CounterCacheService counterCacheService;

    @GetMapping("/counts/batch")
    public ApiResponse<List<InteractionCountResponse>> counts(@RequestParam List<Long> videoIds) {
        List<InteractionCountResponse> counts = videoIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_BATCH_SIZE)
                .map(videoId -> {
                    VideoCounts c = counterCacheService.getCounts(videoId);
                    return new InteractionCountResponse(videoId, c.likeCount(), c.commentCount(),
                            c.shareCount(), c.viewCount(), c.saveCount());
                })
                .toList();
        return ApiResponse.success(counts);
    }
}
```

- [ ] **Step 4: Permit it without authentication**

In `SecurityConfig.java`, add this line **immediately before** the existing single-video `counts` matcher on line 35, so the more specific pattern is evaluated first:

```java
                        .requestMatchers(HttpMethod.GET, "/api/v1/interactions/videos/counts/batch").permitAll()
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -pl services/interaction-service -Dtest=VideoCountsControllerTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add services/interaction-service
git commit -m "feat(interaction-service): add batch video counters endpoint"
```

---

### Task 4: Rate-limit like and unlike

**Files:**
- Create: `services/interaction-service/src/main/java/com/tiktok/interactionservice/exception/LikeRateLimitedException.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/LikeServiceImpl.java`
- Test: `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/LikeServiceImplTest.java`

**Interfaces:**
- Consumes: `InteractionRateLimiter.require(String bucket, Long videoId, Long userId, Supplier<RuntimeException> onExceeded)`, whose Redis key is `interaction:{bucket}:{userId}:{videoId}` with a ceiling of 60 per hour.
- Produces: `LikeRateLimitedException` with code `LIKE_RATE_LIMITED` and HTTP 429; bucket name `"like-rate"`.

- [ ] **Step 1: Create the exception**

`services/interaction-service/src/main/java/com/tiktok/interactionservice/exception/LikeRateLimitedException.java`:

```java
package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many like state changes on one video by one viewer. A repeated like is already a no-op —
 * the LWT refuses the second claim — so what this bounds is the like/unlike/like cycle, which is
 * the one sequence that produces an unbounded stream of events, realtime frames and, once
 * notifications exist, pushes to the video's owner.
 */
public class LikeRateLimitedException extends DomainException {

    public LikeRateLimitedException() {
        super("LIKE_RATE_LIMITED", "Too many like changes for this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
```

- [ ] **Step 2: Write the failing test**

Append to `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/LikeServiceImplTest.java`. Add imports:

```java
import com.tiktok.interactionservice.exception.LikeRateLimitedException;
import org.springframework.data.redis.core.StringRedisTemplate;
```

Add the fields (skip either one that the class already declares) and the test:

```java
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CounterCacheService counterCacheService;

    @Test
    void like_pastTheLimit_isRefusedAndLeavesTheCounterAlone() {
        // The bucket is per (viewer, video, hour) and allows 60. Set it to the ceiling directly
        // rather than calling like/unlike sixty times, which would take sixty Cassandra rounds.
        redisTemplate.opsForValue().set("interaction:like-rate:1:60", "60");

        assertThatThrownBy(() -> likeService.like(60L, 1L))
                .isInstanceOf(LikeRateLimitedException.class);
        assertThat(counterCacheService.getCounts(60L).likeCount()).isZero();
    }
```

Add to the class's existing `@BeforeEach`, so the key cannot leak into another test:

```java
        redisTemplate.delete(redisTemplate.keys("interaction:like-rate:*"));
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -pl services/interaction-service -Dtest=LikeServiceImplTest`
Expected: FAIL — no exception thrown; the like succeeds and the counter reads 1.

- [ ] **Step 4: Apply the limit**

In `LikeServiceImpl.java`, add the import:

```java
import com.tiktok.interactionservice.exception.LikeRateLimitedException;
```

Add the field:

```java
    private final InteractionRateLimiter rateLimiter;
```

As the first statement of `like`, **before** the counter read:

```java
        rateLimiter.require("like-rate", videoId, currentUserId, LikeRateLimitedException::new);
```

In `unlike`, place the same line **after** the `claim == null` early return, so an unlike of a video that was never liked costs nothing — exactly where `SaveServiceImpl.unsave` puts its own:

```java
        rateLimiter.require("like-rate", videoId, currentUserId, LikeRateLimitedException::new);
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -pl services/interaction-service`
Expected: PASS. Existing tests are unaffected — they stay far below 60 changes per (user, video).

- [ ] **Step 6: Commit**

```bash
git add services/interaction-service
git commit -m "feat(interaction-service): rate limit like state changes per viewer and video"
```

---

### Task 5: Realtime fan-out in chat-service

**Files:**
- Modify: `services/chat-service/pom.xml`
- Modify: `services/chat-service/src/main/resources/application.yml`
- Modify: `services/chat-service/src/main/java/com/tiktok/chatservice/ChatServiceApplication.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoFrame.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/CommentFrame.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoTopics.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/SubscriptionTracker.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/DirtyVideoRegistry.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/InteractionCountsClient.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/DirtyVideoFlusher.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoStatsFanout.java`
- Create: `services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoStateFanout.java`
- Test: `services/chat-service/src/test/java/com/tiktok/chatservice/realtime/SubscriptionTrackerTest.java`
- Test: `services/chat-service/src/test/java/com/tiktok/chatservice/realtime/DirtyVideoFlusherTest.java`
- Test: `services/chat-service/src/test/java/com/tiktok/chatservice/realtime/VideoStateFanoutTest.java`

**Interfaces:**
- Consumes: `GET /api/v1/interactions/videos/counts/batch?videoIds=` from Task 3, returning `ApiResponse<List<InteractionCountResponse>>` with fields `videoId, likeCount, commentCount, shareCount, viewCount, saveCount`. Topics `interaction.like-events`, `interaction.comment-events`, `interaction.share-events`, `interaction.save-events`, `interaction.view-events`, `video.video-events`, `admin.moderation-events`.
- Produces: STOMP destinations `/topic/videos.{videoId}` carrying `VideoFrame`, and `/topic/videos.{videoId}.comments` carrying `CommentFrame`, where `{videoId}` is the decimal text form of the Snowflake.

- [ ] **Step 1: Add the dependency**

In `services/chat-service/pom.xml`, inside `<dependencies>`, after the `security-lib` entry:

```xml
        <dependency>
            <groupId>com.tiktok</groupId>
            <artifactId>kafka-lib</artifactId>
            <version>${project.version}</version>
        </dependency>
```

`kafka-lib` brings `spring-kafka` at compile scope and auto-configures the `DefaultErrorHandler` with DLQ publishing, so no local Kafka `@Configuration` is needed. Match the `<version>` style of the sibling `common-lib` / `security-lib` entries already in that file.

- [ ] **Step 2: Configure Kafka and the downstream URI**

In `services/chat-service/src/main/resources/application.yml`, under `spring:`, after the `data:` block:

```yaml
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      # Unique per instance on purpose. enableSimpleBroker keeps STOMP subscriptions in this
      # process's memory, so a record delivered to one replica cannot reach a client attached to
      # another. Making every replica its own consumer group means every replica sees every
      # record and serves only its own clients — a STOMP relay's job, done with one line.
      group-id: realtime-${random.uuid}
      # Nothing here is replayed. A replica that has just started has no business re-sending
      # yesterday's counters, and the frames are snapshots that the next event corrects anyway.
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
```

At the top level of the same file:

```yaml
downstream:
  interaction-service-uri: ${INTERACTION_SERVICE_URI:http://localhost:8085}

realtime:
  flush-interval-millis: 500
```

- [ ] **Step 3: Enable scheduling**

In `ChatServiceApplication.java`, add the import and the annotation:

```java
import org.springframework.scheduling.annotation.EnableScheduling;
```

```java
@EnableScheduling
@SpringBootApplication
public class ChatServiceApplication {
```

- [ ] **Step 4: Write the failing tests**

Create `services/chat-service/src/test/java/com/tiktok/chatservice/realtime/SubscriptionTrackerTest.java`:

```java
package com.tiktok.chatservice.realtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionTrackerTest {

    private final SubscriptionTracker tracker = new SubscriptionTracker();

    @Test
    void aVideoWithNoSubscribersIsNotWatched() {
        assertThat(tracker.isWatched("100")).isFalse();
    }

    @Test
    void subscribingMakesItWatched() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100");

        assertThat(tracker.isWatched("100")).isTrue();
    }

    @Test
    void theLastUnsubscribeStopsIt() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100");
        tracker.subscribed("session-2", "sub-1", "/topic/videos.100");
        tracker.unsubscribed("session-1", "sub-1");

        assertThat(tracker.isWatched("100")).isTrue();

        tracker.unsubscribed("session-2", "sub-1");

        assertThat(tracker.isWatched("100")).isFalse();
    }

    @Test
    void aDisconnectDropsEverySubscriptionOfThatSession() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100");
        tracker.subscribed("session-1", "sub-2", "/topic/videos.101");
        tracker.disconnected("session-1");

        assertThat(tracker.isWatched("100")).isFalse();
        assertThat(tracker.isWatched("101")).isFalse();
    }

    @Test
    void theCommentChannelCountsAsWatchingTheVideo() {
        tracker.subscribed("session-1", "sub-1", "/topic/videos.100.comments");

        assertThat(tracker.isWatched("100")).isTrue();
    }

    @Test
    void destinationsThatAreNotVideoTopicsAreIgnored() {
        tracker.subscribed("session-1", "sub-1", "/topic/conversations.7");

        assertThat(tracker.isWatched("7")).isFalse();
    }
}
```

Create `services/chat-service/src/test/java/com/tiktok/chatservice/realtime/DirtyVideoFlusherTest.java`:

```java
package com.tiktok.chatservice.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DirtyVideoFlusherTest {

    private DirtyVideoRegistry registry;
    private SubscriptionTracker tracker;
    private InteractionCountsClient countsClient;
    private SimpMessagingTemplate messaging;
    private DirtyVideoFlusher flusher;

    @BeforeEach
    void setUp() {
        registry = new DirtyVideoRegistry();
        tracker = new SubscriptionTracker();
        countsClient = mock(InteractionCountsClient.class);
        messaging = mock(SimpMessagingTemplate.class);
        flusher = new DirtyVideoFlusher(registry, tracker, countsClient, messaging);
    }

    @Test
    void manyEventsOnOneVideoProduceOneFrame() {
        tracker.subscribed("s", "1", "/topic/videos.100");
        registry.markDirty("100");
        registry.markDirty("100");
        registry.markDirty("100");
        when(countsClient.fetch(List.of("100")))
                .thenReturn(List.of(VideoFrame.counts("100", 3, 0, 0, 0, 0)));

        flusher.flush();

        verify(messaging, times(1)).convertAndSend(eq("/topic/videos.100"), any(VideoFrame.class));
    }

    @Test
    void aVideoNobodyIsWatchingIsNeverFetched() {
        registry.markDirty("200");

        flusher.flush();

        verifyNoInteractions(countsClient);
        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }

    @Test
    void moreThanFiftyIdsAreSplitAcrossCalls() {
        IntStream.rangeClosed(1, 60).forEach(i -> {
            tracker.subscribed("s", String.valueOf(i), "/topic/videos." + i);
            registry.markDirty(String.valueOf(i));
        });
        when(countsClient.fetch(anyList())).thenReturn(List.of());

        flusher.flush();

        verify(countsClient, times(2)).fetch(anyList());
    }

    @Test
    void theSetIsClearedSoAQuietWindowSendsNothing() {
        tracker.subscribed("s", "1", "/topic/videos.100");
        registry.markDirty("100");
        when(countsClient.fetch(List.of("100")))
                .thenReturn(List.of(VideoFrame.counts("100", 1, 0, 0, 0, 0)));

        flusher.flush();
        flusher.flush();

        verify(countsClient, times(1)).fetch(anyList());
    }

    @Test
    void aFailingFetchDoesNotEscape() {
        tracker.subscribed("s", "1", "/topic/videos.100");
        registry.markDirty("100");
        when(countsClient.fetch(anyList())).thenThrow(new RuntimeException("interaction-service is down"));

        flusher.flush();

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }
}
```

Create `services/chat-service/src/test/java/com/tiktok/chatservice/realtime/VideoStateFanoutTest.java`:

```java
package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VideoStateFanoutTest {

    private SimpMessagingTemplate messaging;
    private VideoStateFanout fanout;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        fanout = new VideoStateFanout(messaging, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void aVisibilityChangeIsForwardedAsAStateFrame() {
        String payload = """
                {"eventId":"e1","occurredAt":"2026-09-09T10:00:00Z","videoId":"100",
                 "userId":7,"visibility":"PRIVATE"}""";

        fanout.onVideoEvent(payload, "VideoVisibilityChangedEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging).convertAndSend(eq("/topic/videos.100"), any(VideoFrame.class));
    }

    @Test
    void aMissingHeaderIsTreatedAsAPublication() {
        String payload = """
                {"eventId":"e2","occurredAt":"2026-09-09T10:00:00Z","videoId":"101","userId":7}""";

        fanout.onVideoEvent(payload, null);

        verify(messaging).convertAndSend(eq("/topic/videos.101"), any(VideoFrame.class));
    }

    @Test
    void anUnknownEventTypeIsIgnoredWithoutThrowing() {
        fanout.onVideoEvent("{\"videoId\":\"102\"}",
                "SomethingElseEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }

    @Test
    void malformedJsonIsIgnoredWithoutThrowing() {
        fanout.onVideoEvent("not json", "VideoPublishedEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }

    @Test
    void aTakedownIsForwardedFromTheModerationTopic() {
        String payload = """
                {"eventId":"e3","occurredAt":"2026-09-09T10:00:00Z","videoId":"103",
                 "adminId":1,"reason":"nudity"}""";

        fanout.onModerationEvent(payload, "VideoTakenDownEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging).convertAndSend(eq("/topic/videos.103"), any(VideoFrame.class));
    }

    @Test
    void aModerationEventWithoutAVideoIdIsIgnored() {
        String payload = """
                {"eventId":"e4","occurredAt":"2026-09-09T10:00:00Z","userId":9,"adminId":1}""";

        fanout.onModerationEvent(payload, "UserBannedEvent".getBytes(StandardCharsets.UTF_8));

        verify(messaging, never()).convertAndSend(any(String.class), any(VideoFrame.class));
    }
}
```

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./mvnw test -pl services/chat-service`
Expected: FAIL — compile errors, none of the `realtime` classes exist.

- [ ] **Step 6: Write the frames and the destination helper**

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoFrame.java`:

```java
package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message on {@code /topic/videos.{videoId}}. Two shapes share the record, told apart by
 * {@code type}: {@code "counts"} carries the numbers and {@code "state"} carries status or
 * visibility. Unset fields are dropped from the JSON, so a state frame is not a wall of zeros a
 * client might mistake for real counters.
 *
 * <p>{@code videoId} is a String because it holds a Snowflake, and JavaScript's
 * {@code JSON.parse} silently rounds a 64-bit integer.
 *
 * <p>The counts are a snapshot, never a delta. A dropped frame then costs one stale render until
 * the next event; a dropped delta would be a number that stays wrong until the page reloads,
 * which is the bug this whole feature exists to fix.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoFrame(
        String type,
        String videoId,
        Long likeCount,
        Long commentCount,
        Long shareCount,
        Long viewCount,
        Long saveCount,
        String status,
        String visibility
) {

    public static VideoFrame counts(String videoId, long likeCount, long commentCount,
                                    long shareCount, long viewCount, long saveCount) {
        return new VideoFrame("counts", videoId, likeCount, commentCount, shareCount,
                viewCount, saveCount, null, null);
    }

    public static VideoFrame status(String videoId, String status) {
        return new VideoFrame("state", videoId, null, null, null, null, null, status, null);
    }

    public static VideoFrame visibility(String videoId, String visibility) {
        return new VideoFrame("state", videoId, null, null, null, null, null, null, visibility);
    }
}
```

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/CommentFrame.java`:

```java
package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One message on {@code /topic/videos.{videoId}.comments}. Ids are Strings for the same reason as
 * {@link VideoFrame}. Not coalesced: two comments are two facts, and gathering them into one
 * frame would lose one of them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommentFrame(
        String type,
        String videoId,
        String commentId,
        String userId,
        String content,
        String createdAt
) {

    public static CommentFrame created(String videoId, String commentId, String userId,
                                       String content, String createdAt) {
        return new CommentFrame("comment.created", videoId, commentId, userId, content, createdAt);
    }

    public static CommentFrame deleted(String videoId, String commentId) {
        return new CommentFrame("comment.deleted", videoId, commentId, null, null, null);
    }
}
```

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoTopics.java`:

```java
package com.tiktok.chatservice.realtime;

/**
 * The one place the realtime destinations are spelled out. The publisher and the subscription
 * tracker have to agree on the shape, and a mismatch is invisible — frames go to a destination
 * nobody listens on, and nothing logs an error.
 */
public final class VideoTopics {

    private static final String PREFIX = "/topic/videos.";
    private static final String COMMENTS_SUFFIX = ".comments";

    private VideoTopics() {
    }

    public static String video(String videoId) {
        return PREFIX + videoId;
    }

    public static String comments(String videoId) {
        return PREFIX + videoId + COMMENTS_SUFFIX;
    }

    /** @return the videoId a destination refers to, or null if it is not a video destination. */
    public static String videoIdOf(String destination) {
        if (destination == null || !destination.startsWith(PREFIX)) {
            return null;
        }
        String rest = destination.substring(PREFIX.length());
        if (rest.endsWith(COMMENTS_SUFFIX)) {
            rest = rest.substring(0, rest.length() - COMMENTS_SUFFIX.length());
        }
        return rest.isEmpty() ? null : rest;
    }
}
```

- [ ] **Step 7: Write the subscription tracker**

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/SubscriptionTracker.java`:

```java
package com.tiktok.chatservice.realtime;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which videos this instance currently has a listener for. Without it the flusher would fetch
 * counters for every video that moved anywhere on the platform, which is most of them, and throw
 * nearly all of the answers away.
 *
 * <p>Per instance, deliberately. Every replica consumes every record (see the group-id in
 * application.yml), so each one asks only about the videos its own clients are watching.
 */
@Component
public class SubscriptionTracker {

    /** videoId to the number of live subscriptions on it, across every session on this instance. */
    private final Map<String, AtomicInteger> watchers = new ConcurrentHashMap<>();

    /** (sessionId, subscriptionId) to the videoId it watches, so an unsubscribe can undo it. */
    private final Map<String, String> bySubscription = new ConcurrentHashMap<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        subscribed(accessor.getSessionId(), accessor.getSubscriptionId(), accessor.getDestination());
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        unsubscribed(accessor.getSessionId(), accessor.getSubscriptionId());
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        disconnected(event.getSessionId());
    }

    void subscribed(String sessionId, String subscriptionId, String destination) {
        String videoId = VideoTopics.videoIdOf(destination);
        if (videoId == null || sessionId == null || subscriptionId == null) {
            return;
        }
        bySubscription.put(key(sessionId, subscriptionId), videoId);
        watchers.computeIfAbsent(videoId, id -> new AtomicInteger()).incrementAndGet();
    }

    void unsubscribed(String sessionId, String subscriptionId) {
        release(bySubscription.remove(key(sessionId, subscriptionId)));
    }

    void disconnected(String sessionId) {
        if (sessionId == null) {
            return;
        }
        String prefix = sessionId + " ";
        bySubscription.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                release(entry.getValue());
                return true;
            }
            return false;
        });
    }

    public boolean isWatched(String videoId) {
        AtomicInteger count = watchers.get(videoId);
        return count != null && count.get() > 0;
    }

    /**
     * Removes the entry when it reaches zero rather than leaving a zeroed counter behind: this map
     * is keyed by videoId and would otherwise grow for the lifetime of the process, one entry per
     * video anyone ever scrolled past.
     */
    private void release(String videoId) {
        if (videoId == null) {
            return;
        }
        watchers.computeIfPresent(videoId, (id, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    private static String key(String sessionId, String subscriptionId) {
        return sessionId + " " + subscriptionId;
    }
}
```

- [ ] **Step 8: Write the dirty registry**

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/DirtyVideoRegistry.java`:

```java
package com.tiktok.chatservice.realtime;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The videos whose counters have moved since the last flush. A set, so a hundred likes in one
 * window are one entry — this is where a like/unlike burst stops being a burst.
 */
@Component
public class DirtyVideoRegistry {

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public void markDirty(String videoId) {
        if (videoId != null && !videoId.isBlank()) {
            dirty.add(videoId);
        }
    }

    /**
     * Takes the current contents and removes exactly those. Events arriving during the flush land
     * in the set and go out in the next window rather than being dropped with the drained ones.
     */
    public Set<String> drain() {
        Set<String> drained = Set.copyOf(dirty);
        dirty.removeAll(drained);
        return drained;
    }
}
```

- [ ] **Step 9: Write the counts client**

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/InteractionCountsClient.java`:

```java
package com.tiktok.chatservice.realtime;

import com.tiktok.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Reads counters from interaction-service over HTTP. Not from Cassandra — interaction-service owns
 * that keyspace, and a second reader of another service's database is what rule §6 forbids.
 */
@Component
public class InteractionCountsClient {

    private static final ParameterizedTypeReference<ApiResponse<List<CountsRow>>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public InteractionCountsClient(RestClient.Builder builder,
                                   @Value("${downstream.interaction-service-uri}") String baseUri) {
        this.restClient = builder.baseUrl(baseUri).build();
    }

    /** @return one frame per video the service answered for; empty if it answered with nothing. */
    public List<VideoFrame> fetch(List<String> videoIds) {
        if (videoIds.isEmpty()) {
            return List.of();
        }
        ApiResponse<List<CountsRow>> response = restClient.get()
                .uri(uri -> uri.path("/api/v1/interactions/videos/counts/batch")
                        .queryParam("videoIds", String.join(",", videoIds))
                        .build())
                .retrieve()
                .body(RESPONSE_TYPE);

        if (response == null || response.getData() == null) {
            return List.of();
        }
        return response.getData().stream()
                .map(row -> VideoFrame.counts(String.valueOf(row.videoId()), row.likeCount(),
                        row.commentCount(), row.shareCount(), row.viewCount(), row.saveCount()))
                .toList();
    }

    /**
     * A local mirror of interaction-service's InteractionCountResponse. Copied rather than shared,
     * because event-schema carries events and this is an HTTP contract; a shared DTO here would
     * tie the two services' releases together for no gain.
     */
    private record CountsRow(
            Long videoId,
            long likeCount,
            long commentCount,
            long shareCount,
            long viewCount,
            long saveCount
    ) {
    }
}
```

Check how `common-lib`'s `ApiResponse` exposes its payload before running — if the accessor is not `getData()`, adjust this method to match `libs/common-lib/src/main/java/com/tiktok/common/response/ApiResponse.java`.

- [ ] **Step 10: Write the flusher**

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/DirtyVideoFlusher.java`:

```java
package com.tiktok.chatservice.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns everything that moved in the last window into at most one frame per video.
 *
 * <p>This is the anti-spam layer that does not care where the spam came from: a viewer hammering
 * the like button, a hot video taking two hundred likes a second, and a redelivered batch of
 * Kafka records all collapse into the same two frames a second.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirtyVideoFlusher {

    /** interaction-service caps its batch endpoint at 50; asking for more silently drops the rest. */
    private static final int BATCH_SIZE = 50;

    private final DirtyVideoRegistry registry;
    private final SubscriptionTracker subscriptions;
    private final InteractionCountsClient countsClient;
    private final SimpMessagingTemplate messaging;

    @Scheduled(fixedDelayString = "${realtime.flush-interval-millis}")
    public void flush() {
        List<String> watched = registry.drain().stream()
                .filter(subscriptions::isWatched)
                .toList();

        for (int from = 0; from < watched.size(); from += BATCH_SIZE) {
            send(watched.subList(from, Math.min(from + BATCH_SIZE, watched.size())));
        }
    }

    /**
     * A failed batch is dropped rather than retried or rethrown. Retrying would push the next
     * window's work behind it, and throwing out of a @Scheduled method only fills the log — while
     * the cost of dropping it is one stale render, corrected by the next event on that video, on
     * top of the REST value the client already has.
     */
    private void send(List<String> batch) {
        try {
            for (VideoFrame frame : countsClient.fetch(batch)) {
                messaging.convertAndSend(VideoTopics.video(frame.videoId()), frame);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read counters for {} videos, skipping this window: {}",
                    batch.size(), e.getMessage());
        }
    }
}
```

- [ ] **Step 11: Write the stats fan-out**

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoStatsFanout.java`:

```java
package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Every topic whose records mean "a counter on this video moved". None of them is read for its
 * contents beyond the videoId: the numbers come from interaction-service at flush time, so this
 * listener never has to know which counter changed or by how much, and a new counter needs no
 * change here.
 *
 * <p>The comment topic is the exception — it also forwards the comment itself, because a comment
 * is content and there is no counter to read it back from.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStatsFanout {

    private final DirtyVideoRegistry registry;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = {
            "interaction.like-events",
            "interaction.share-events",
            "interaction.save-events",
            "interaction.view-events"
    })
    public void onCounterEvent(String payload) {
        JsonNode node = parse(payload);
        if (node != null) {
            registry.markDirty(text(node, "videoId"));
        }
    }

    @KafkaListener(topics = "interaction.comment-events")
    public void onCommentEvent(String payload,
                               @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        String videoId = text(node, "videoId");
        if (videoId == null) {
            return;
        }
        registry.markDirty(videoId);

        // interaction.comment-events carries two shapes. Routing is on the header, never on the
        // payload: Jackson would happily read a deletion as a creation with every missing field
        // null, and nothing would log an error.
        String eventType = eventTypeHeader == null ? "" : new String(eventTypeHeader);
        CommentFrame frame = switch (eventType) {
            case "CommentCreatedEvent" -> CommentFrame.created(videoId, text(node, "commentId"),
                    text(node, "userId"), text(node, "content"), text(node, "occurredAt"));
            case "CommentDeletedEvent" -> CommentFrame.deleted(videoId, text(node, "commentId"));
            default -> null;
        };
        if (frame == null) {
            log.debug("Comment event with eventType={} not forwarded", eventType);
            return;
        }
        messaging.convertAndSend(VideoTopics.comments(videoId), frame);
    }

    /**
     * Returns null instead of throwing. A record this listener cannot read is not worth three
     * retries and a dead-letter entry — realtime is a layer on top of REST, and dropping one frame
     * costs a stale render until the next event, while a poisoned partition would stop every
     * video's updates on this instance.
     */
    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Unreadable realtime event dropped: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
```

- [ ] **Step 12: Write the state fan-out**

`services/chat-service/src/main/java/com/tiktok/chatservice/realtime/VideoStateFanout.java`:

```java
package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Publication, deletion, visibility and moderation. Sent the moment they arrive rather than
 * coalesced: each happens once in a video's life, and a video that has just been taken down has to
 * leave the screen now, not at the end of the window.
 *
 * <p>The frame is a hint, not the truth. VideoRestoredEvent does not carry the status the video is
 * being restored to — video-service restores it to whatever it was before the takedown — so the
 * client refetches the video on any state frame instead of trusting the value.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStateFanout {

    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events")
    public void onVideoEvent(String payload,
                             @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        String videoId = text(node, "videoId");
        if (videoId == null) {
            return;
        }

        // A missing header means VideoPublishedEvent: producers older than the mixed topic sent
        // only that type.
        String eventType = eventTypeHeader == null
                ? "VideoPublishedEvent"
                : new String(eventTypeHeader);

        VideoFrame frame = switch (eventType) {
            case "VideoPublishedEvent" -> VideoFrame.status(videoId, "PUBLISHED");
            // A videoId nothing here has ever heard of is normal: a video deleted before its
            // publication was announced still emits this. Publishing to a destination with no
            // subscribers is a no-op, which is exactly the required no-op.
            case "VideoDeletedEvent" -> VideoFrame.status(videoId, "DELETED");
            case "VideoVisibilityChangedEvent" -> VideoFrame.visibility(videoId, text(node, "visibility"));
            default -> null;
        };
        publish(videoId, frame, eventType);
    }

    @KafkaListener(topics = "admin.moderation-events")
    public void onModerationEvent(String payload,
                                  @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        if (eventTypeHeader == null) {
            // Other services warn about this; it is not this listener's job to dead-letter another
            // service's producer bug.
            log.debug("Moderation event without an eventType header, dropped");
            return;
        }
        JsonNode node = parse(payload);
        if (node == null) {
            return;
        }
        String videoId = text(node, "videoId");
        if (videoId == null) {
            // UserBannedEvent and friends share this topic and have no videoId.
            return;
        }

        String eventType = new String(eventTypeHeader);
        VideoFrame frame = switch (eventType) {
            case "VideoTakenDownEvent" -> VideoFrame.status(videoId, "TAKEN_DOWN");
            case "VideoRestoredEvent" -> VideoFrame.status(videoId, "PUBLISHED");
            default -> null;
        };
        publish(videoId, frame, eventType);
    }

    private void publish(String videoId, VideoFrame frame, String eventType) {
        if (frame == null) {
            log.debug("State eventType={} not forwarded", eventType);
            return;
        }
        messaging.convertAndSend(VideoTopics.video(videoId), frame);
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Unreadable state event dropped: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
```

- [ ] **Step 13: Run the tests to verify they pass**

Run: `./mvnw test -pl services/chat-service`
Expected: PASS — all three new test classes green.

- [ ] **Step 14: Verify the fan-out by hand**

```bash
make infra-up
make run-interaction
make run-chat
```

Start the services through `make` — launched any other way they miss `JWT_SECRET` and every request 401s at the gateway. Subscribe from a STOMP client to `/topic/videos.<a published videoId>`, like that video through the gateway, and watch a `counts` frame arrive within a second.

- [ ] **Step 15: Commit**

```bash
git add services/chat-service
git commit -m "feat(chat-service): fan out video counters and state over STOMP"
```

---

### Task 6: Frontend subscription and like debounce

**Files (in the sibling repository `../tiktok-cloned`):**
- Create: `src/hooks/useLikeDebounce.ts`
- Create: `src/lib/realtime/stompClient.ts`
- Create: `src/hooks/useVideoRealtime.ts`
- Modify: the feed component that renders the video list
- Modify: the like button component
- Test: `src/hooks/__tests__/useLikeDebounce.test.ts`

**Interfaces:**
- Consumes: the `/ws` STOMP endpoint through the gateway; destinations `/topic/videos.{videoId}` and `/topic/videos.{videoId}.comments`; the frame shapes from Task 5.
- Produces: `useLikeDebounce(serverLiked: boolean, send: (liked: boolean) => Promise<unknown>)` returning `{ liked, toggle }`; `useVideoRealtime(videoIds: string[], token: string | null, onFrame: (frame: VideoFrame) => void)`.

Exact paths for the feed and like-button components are not fixed by this plan. Find them first by grepping `../tiktok-cloned` for the existing call to `/api/v1/interactions/videos`, and follow whatever conventions that repository already uses for hooks and API clients.

- [ ] **Step 1: Install the STOMP client**

```bash
cd ../tiktok-cloned && npm install @stomp/stompjs
```

- [ ] **Step 2: Write the failing debounce test**

Create `src/hooks/__tests__/useLikeDebounce.test.ts`:

```ts
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useLikeDebounce } from "../useLikeDebounce";

describe("useLikeDebounce", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("sends nothing when the taps cancel out", () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useLikeDebounce(false, send));

    act(() => {
      result.current.toggle();
      result.current.toggle();
    });
    act(() => {
      vi.advanceTimersByTime(600);
    });

    expect(send).not.toHaveBeenCalled();
  });

  it("sends one request for a burst that ends on the opposite state", () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useLikeDebounce(false, send));

    act(() => {
      result.current.toggle();
      result.current.toggle();
      result.current.toggle();
    });
    act(() => {
      vi.advanceTimersByTime(600);
    });

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith(true);
  });

  it("shows the new state immediately, before anything is sent", () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const { result } = renderHook(() => useLikeDebounce(false, send));

    act(() => {
      result.current.toggle();
    });

    expect(result.current.liked).toBe(true);
    expect(send).not.toHaveBeenCalled();
  });
});
```

If the repository uses Jest rather than Vitest, swap `vi` for `jest` and the fake-timer calls accordingly — check `package.json` first.

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd ../tiktok-cloned && npx vitest run src/hooks/__tests__/useLikeDebounce.test.ts`
Expected: FAIL — cannot resolve `../useLikeDebounce`.

- [ ] **Step 4: Write the debounce hook**

Create `src/hooks/useLikeDebounce.ts`:

```ts
import { useCallback, useEffect, useRef, useState } from "react";

const WINDOW_MS = 500;

/**
 * Optimistic like state with one request per burst.
 *
 * The UI flips on every tap; only the state the user settles on is sent, and only when it differs
 * from what the server already holds. Ten taps that land back where they started cost zero
 * requests — which is the point, because every request that does go out becomes a Kafka event, a
 * realtime frame, and eventually a notification to the video's owner.
 */
export function useLikeDebounce(
  serverLiked: boolean,
  send: (liked: boolean) => Promise<unknown>,
) {
  const [liked, setLiked] = useState(serverLiked);
  const confirmed = useRef(serverLiked);
  const desired = useRef(serverLiked);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // A counts frame or a refetch can move the server's value while nothing is pending.
  useEffect(() => {
    if (timer.current === null) {
      confirmed.current = serverLiked;
      desired.current = serverLiked;
      setLiked(serverLiked);
    }
  }, [serverLiked]);

  const flush = useCallback(() => {
    timer.current = null;
    const target = desired.current;
    if (target === confirmed.current) {
      return;
    }
    confirmed.current = target;
    send(target).catch(() => {
      // Includes a 429 from the server-side bucket. The server's value is the one that counts.
      confirmed.current = !target;
      desired.current = !target;
      setLiked(!target);
    });
  }, [send]);

  const toggle = useCallback(() => {
    desired.current = !desired.current;
    setLiked(desired.current);
    if (timer.current !== null) {
      clearTimeout(timer.current);
    }
    timer.current = setTimeout(flush, WINDOW_MS);
  }, [flush]);

  // Leaving the page mid-window must not drop the tap.
  useEffect(() => {
    return () => {
      if (timer.current !== null) {
        clearTimeout(timer.current);
        flush();
      }
    };
  }, [flush]);

  return { liked, toggle };
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd ../tiktok-cloned && npx vitest run src/hooks/__tests__/useLikeDebounce.test.ts`
Expected: PASS.

- [ ] **Step 6: Write the shared STOMP client**

Create `src/lib/realtime/stompClient.ts`:

```ts
import { Client } from "@stomp/stompjs";

let client: Client | null = null;

/**
 * One connection for the whole app. If the chat feature opens its own, move it onto this one —
 * a second socket buys nothing and doubles the handshakes.
 */
export function getStompClient(token: string): Client {
  if (client) {
    return client;
  }
  const wsBase = (process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080")
    .replace(/^http/, "ws");

  client = new Client({
    brokerURL: `${wsBase}/ws`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 3000,
  });
  client.activate();
  return client;
}
```

Check how the app currently reaches the gateway before keeping that default — the project proxies rather than using CORS, so a helper for the base URL may already exist.

- [ ] **Step 7: Write the subscription hook**

Create `src/hooks/useVideoRealtime.ts`:

```ts
import { useEffect, useRef } from "react";
import type { StompSubscription } from "@stomp/stompjs";
import { getStompClient } from "@/lib/realtime/stompClient";

export type VideoFrame = {
  type: "counts" | "state";
  videoId: string;
  likeCount?: number;
  commentCount?: number;
  shareCount?: number;
  viewCount?: number;
  saveCount?: number;
  status?: string;
  visibility?: string;
};

/**
 * Subscribes to every video currently rendered, and drops the ones that scroll away.
 *
 * ids are strings and must stay strings: they are Snowflakes, and JSON.parse rounds a 64-bit
 * integer into a different video.
 */
export function useVideoRealtime(
  videoIds: string[],
  token: string | null,
  onFrame: (frame: VideoFrame) => void,
) {
  const subscriptions = useRef(new Map<string, StompSubscription>());
  const handler = useRef(onFrame);
  handler.current = onFrame;

  useEffect(() => {
    if (!token) {
      return;
    }
    const client = getStompClient(token);
    const live = subscriptions.current;

    const sync = () => {
      if (!client.connected) {
        return;
      }
      for (const id of videoIds) {
        if (!live.has(id)) {
          live.set(
            id,
            client.subscribe(`/topic/videos.${id}`, (message) => {
              handler.current(JSON.parse(message.body) as VideoFrame);
            }),
          );
        }
      }
      for (const [id, subscription] of live) {
        if (!videoIds.includes(id)) {
          subscription.unsubscribe();
          live.delete(id);
        }
      }
    };

    sync();
    // A reconnect invalidates every subscription, so re-subscribe rather than assume they survived.
    client.onConnect = sync;

    return () => {
      for (const [, subscription] of live) {
        subscription.unsubscribe();
      }
      live.clear();
    };
  }, [videoIds, token]);
}
```

- [ ] **Step 8: Wire the feed**

In the feed component, collect the ids of the videos currently rendered and pass them to `useVideoRealtime`. On a `counts` frame, write the numbers into that video's state. On a `state` frame, refetch that one video through the existing API client — the frame is a hint, not the truth.

- [ ] **Step 9: Wire the like button**

Replace the direct like/unlike call with `useLikeDebounce`, passing the server's current value and a `send` that calls the existing `POST` / `DELETE /api/v1/interactions/videos/{videoId}/like`.

- [ ] **Step 10: Subscribe the comment sheet**

When the comment sheet opens for a video, subscribe `/topic/videos.{id}.comments` and prepend a `comment.created` frame's comment to the list; remove the comment named by a `comment.deleted` frame. Unsubscribe when the sheet closes — nobody is looking at that channel while it is shut.

- [ ] **Step 11: Verify by hand**

Start the backend with `make infra-up` and the services through `make`, then open the app in two browsers. Like a video in one and watch the number move in the other without a reload. Hammer the like button and confirm in the network tab that at most one request leaves per 500ms.

- [ ] **Step 12: Commit**

```bash
cd ../tiktok-cloned
git add src/hooks src/lib/realtime package.json package-lock.json
git commit -m "feat: subscribe rendered videos to realtime counters and debounce likes"
```

---

## Notes for whoever picks this up

- Tasks 1–4 are backend-only and independent of 5–6. They can ship on their own.
- Task 6 lives in a different repository (`../tiktok-cloned`) and gets its own commits there.
- The notification coalescing design in spec §6 is deliberately **not** in this plan. It is written down so that `Notification` gets `actorIds`, `actorCount` and a unique `(recipientId, targetId, type)` index the first time that entity is touched; building the rest of it is a separate piece of work.
- Before any production deploy, `grep -rn "DEV ONLY" services/` must still return empty.
