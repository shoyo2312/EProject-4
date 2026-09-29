# Private Account Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the web client's "Private account" switch real on the backend. A private account gets follow requests, and its videos, followers/following lists and Reposts tab are kept to its followers.

**Architecture:** user-service owns the flag (`user_profiles.private_account`) and the requests (`user_follow_requests`), and announces both through its outbox, one topic per event type. video-service keeps its own copy of who is private: the `owner_privacy` collection, plus `Video.ownerPrivate` on every document. It asks user-service one batched question per request: which of these private owners does the viewer follow. A private owner's PUBLIC videos are re-announced with the audience `FOLLOWERS`, which search-service, recommendation-service and chat-service already treat as hidden. interaction-service gates the Reposts tab, and notification-service tells both sides about requests.

**Tech Stack:** Java 21, Spring Boot 3.3, Maven reactor (`./mvnw`), PostgreSQL + Flyway (user-service), MongoDB (video-service, notification-service), Cassandra + Redis (interaction-service), Kafka through `kafka-lib`'s `OutboxDispatcher`, MapStruct, JUnit 5 + AssertJ + Mockito + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-26-private-account-design.md`

All paths are relative to the worktree root, `/Users/minhhung/Projects/EProject/EProject4/tiktok-backend-private-account`. Every command runs from there.

## Global Constraints

- **Worktree and branch.** Work only in this worktree, on branch `feat/private-account`.
  - Another session works on `feat/admin-realtime` in its own worktree.
  - Never touch `services/admin-service` or `libs/event-schema/src/main/java/com/tiktok/event/admin`.
- **Never run `./mvnw install`.** `~/.m2` is shared with that session. Build upstream modules in the reactor with `-am`.
- **Test commands.** Testcontainers need Docker running.
  - One class: `./mvnw test -pl services/<name> -am -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`
  - Full suite: `./mvnw test -pl services/<name> -am`
- **Commits.** The touched service's full suite passes before each commit. One commit per task. Never push.
- **Code shape.** Constructor injection through `@RequiredArgsConstructor`; DTOs and events are records.
- **Exceptions.** Domain exceptions extend common-lib's `ForbiddenException`, `ConflictException`, `ResourceNotFoundException` or `BadRequestException`. The new codes are exactly:
  - `PRIVATE_ACCOUNT` (403)
  - `ALREADY_REQUESTED` (409)
  - `FOLLOW_REQUEST_NOT_FOUND` (404)
- **Topics.** One event type per topic, and no `eventType` header:

  | Aggregate type | Topic | Key |
  |---|---|---|
  | `USER_FOLLOW` | `user.follow-events` | `followingId` |
  | `FOLLOW_REQUEST` | `user.follow-request-events` | `targetId` |
  | `USER_PRIVACY` | `user.privacy-events` | `userId` |

- **Effective audience.** `visibility == PUBLIC && ownerPrivate ? "FOLLOWERS" : visibility`.
  - It goes on `VideoPublishedEvent` and `VideoVisibilityChangedEvent` only.
  - The stored `visibility` and `VideoResponse.visibility` never change.
- **Readers tolerate the old shape.**
  - A missing `ownerPrivate` reads `false`.
  - No `owner_privacy` document means public.
  - A missing `requestApproved` reads `false`.
- **Follow checks fail closed.** A lookup that fails means "does not follow".
- **Batch cap.** Batch ids are capped at 100 (`TOO_MANY_PROFILE_IDS`).
- **Comments.** Comments say why, never what, at the density of the code around them. A deliberate ceiling gets a `ponytail:` comment that names the upgrade path.
- **Commit trailer.** Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A rejected requester asks again.** That is a new pending request, not a 409. Pinned by `reject_thenAskingAgain_isANewRequest` (Task 3).
2. **One PATCH sets `privateAccount` and also carries an invalid sleep window.** Nothing changes and no event is queued. Pinned by `privacy_inAPatchThatFailsValidation_changesNothing` (Task 4).
3. **A privacy event is redelivered after a partial failure.** Its first attempt recorded the flag but failed before stamping the videos, and the redelivery must still stamp them. That is why the spec's `<` becomes `<=`. Pinned by `aRedeliveryAfterAFailedFanOut_stillStampsTheVideos` (Task 7).
4. **A batch mixes four kinds of owner:** the viewer's own videos, a followed private owner, an unfollowed private owner and a public owner. Exactly one follow call is made, naming only the two private owners, and the viewer's own videos stay. Pinned by `getByIds_asksOnceForThePrivateOwnersOnThePage_andNeverAboutTheViewer` (Task 8).
5. **A private owner names themselves in their own Following feed.** They still see their videos, and no follow call is made. Pinned by `getFollowingFeed_aPrivateOwnerStillSeesTheirOwn` (Task 9).

---

## File Structure

New files, one responsibility each. Everything else is a change to an existing file, listed in its task.

**event-schema**

| File | Responsibility |
|---|---|
| `libs/event-schema/src/main/java/com/tiktok/event/user/UserPrivacyChangedEvent.java` | An account became private or public |
| `libs/event-schema/src/main/java/com/tiktok/event/user/FollowRequestedEvent.java` | Someone asked to follow a private account |

**user-service** (Java paths under `services/user-service/src/main/java/com/tiktok/userservice/`)

| File | Responsibility |
|---|---|
| `services/user-service/src/main/resources/db/migration/V11__add_private_account_to_user_profiles.sql` | The flag |
| `services/user-service/src/main/resources/db/migration/V12__create_user_follow_requests_table.sql` | Pending requests |
| `entity/FollowRequest.java` | One pending request |
| `repository/FollowRequestRepository.java` | Its queries |
| `controller/FollowRequestController.java` | The owner's side: list, approve, reject |
| `exception/AlreadyRequestedException.java` | `ALREADY_REQUESTED` (409) |
| `exception/FollowRequestNotFoundException.java` | `FOLLOW_REQUEST_NOT_FOUND` (404) |
| `exception/PrivateAccountException.java` | `PRIVATE_ACCOUNT` (403) on the lists |

**video-service** (under `services/video-service/src/main/java/com/tiktok/videoservice/`)

| File | Responsibility |
|---|---|
| `entity/OwnerPrivacy.java` | video-service's copy of who is private |
| `repository/OwnerPrivacyRepository.java` | Reads of that copy |
| `event/consumer/UserPrivacyEventConsumer.java` | Keeps the copy and stamps the owner's videos |
| `client/FollowClient.java` | The batched "which of these owners does the viewer follow" |
| `exception/PrivateAccountException.java` | `PRIVATE_ACCOUNT` (403) on the profile grid |

**interaction-service** (under `services/interaction-service/src/main/java/com/tiktok/interactionservice/`)

| File | Responsibility |
|---|---|
| `exception/PrivateAccountException.java` | `PRIVATE_ACCOUNT` (403) on the Reposts tab |

**notification-service** (under `services/notification-service/src/main/java/com/tiktok/notificationservice/`)

| File | Responsibility |
|---|---|
| `event/consumer/FollowRequestEventConsumer.java` | `FOLLOW_REQUEST` to the owner |

---

### Task 1: Events and outbox routing

**Files:**
- Create: `libs/event-schema/src/main/java/com/tiktok/event/user/UserPrivacyChangedEvent.java`
- Create: `libs/event-schema/src/main/java/com/tiktok/event/user/FollowRequestedEvent.java`
- Modify: `libs/event-schema/src/main/java/com/tiktok/event/user/UserFollowChangedEvent.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/event/producer/UserEventPublisher.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/event/producer/OutboxPublisher.java`
- Test (new): `services/user-service/src/test/java/com/tiktok/userservice/event/producer/UserEventPublisherTest.java`
- Test (new): `services/user-service/src/test/java/com/tiktok/userservice/event/producer/OutboxPublisherTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record UserPrivacyChangedEvent(String eventId, Instant occurredAt, Long userId, boolean privateAccount)`, with `static of(Long userId, boolean privateAccount)`.
  - `record FollowRequestedEvent(String eventId, Instant occurredAt, Long requesterId, Long targetId)`, with `static of(Long requesterId, Long targetId)`.
  - `UserFollowChangedEvent` gains a last component, `boolean requestApproved`.
    - `of(followerId, followingId, followed)` still works and sets it to false.
    - `of(followerId, followingId, followed, requestApproved)` is new.
  - `UserEventPublisher` methods:
    - `publishFollowChanged(Long followerId, Long followingId, boolean followed)`, unchanged.
    - `publishFollowChanged(Long followerId, Long followingId, boolean followed, boolean requestApproved)`
    - `publishFollowRequested(Long requesterId, Long targetId)`
    - `publishPrivacyChanged(Long userId, boolean privateAccount)`
  - Package-private constants on `UserEventPublisher`:
    - `FOLLOW_AGGREGATE = "USER_FOLLOW"`
    - `FOLLOW_REQUEST_AGGREGATE = "FOLLOW_REQUEST"`
    - `PRIVACY_AGGREGATE = "USER_PRIVACY"`

- [ ] **Step 1: Write the failing tests**

Create `services/user-service/src/test/java/com/tiktok/userservice/event/producer/UserEventPublisherTest.java`:

```java
package com.tiktok.userservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserEventPublisherTest {

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final UserEventPublisher publisher =
            new UserEventPublisher(outboxEventRepository, new ObjectMapper().findAndRegisterModules());

    /**
     * The aggregate type is what OutboxPublisher routes on and the aggregate id is the record key,
     * so a wrong one here is an event on the wrong topic, or out of order on the right one.
     */
    @Test
    void eachAnnouncement_isQueuedUnderItsAggregateType_andKeyedByWhoItConcerns() {
        publisher.publishFollowChanged(1L, 2L, true, true);
        publisher.publishFollowRequested(3L, 4L);
        publisher.publishPrivacyChanged(5L, true);

        ArgumentCaptor<OutboxEvent> rows = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository, times(3)).save(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(OutboxEvent::getAggregateType, OutboxEvent::getAggregateId, OutboxEvent::getEventType)
                .containsExactly(
                        tuple("USER_FOLLOW", "2", "UserFollowChangedEvent"),
                        tuple("FOLLOW_REQUEST", "4", "FollowRequestedEvent"),
                        tuple("USER_PRIVACY", "5", "UserPrivacyChangedEvent"));
        assertThat(rows.getAllValues().get(0).getPayload()).contains("\"requestApproved\":true");
        assertThat(rows.getAllValues().get(1).getPayload()).contains("\"requesterId\":3", "\"targetId\":4");
        assertThat(rows.getAllValues().get(2).getPayload()).contains("\"userId\":5", "\"privateAccount\":true");
    }

    @Test
    void aPlainFollow_isNotAnApprovedRequest() {
        publisher.publishFollowChanged(1L, 2L, true);

        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(row.capture());
        assertThat(row.getValue().getPayload()).contains("\"followed\":true", "\"requestApproved\":false");
    }
}
```

Create `services/user-service/src/test/java/com/tiktok/userservice/event/producer/OutboxPublisherTest.java`:

```java
package com.tiktok.userservice.event.producer;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaOperations<String, String> kafkaOperations;

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outboxEventRepository,
                new OutboxDispatcher(kafkaOperations, Duration.ofSeconds(1)));
    }

    /**
     * One event type per topic is what lets every consumer parse without routing, so a row on the
     * wrong topic is read as the wrong event — silently.
     */
    @Test
    void eachAggregateTypeGoesToItsOwnTopic_keyedByItsAggregate() {
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(
                row(1L, "USER_FOLLOW", "2"),
                row(2L, "FOLLOW_REQUEST", "3"),
                row(3L, "USER_PRIVACY", "4")));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(3)).send(sent.capture());
        assertThat(sent.getAllValues())
                .extracting(ProducerRecord::topic, ProducerRecord::key)
                .containsExactly(
                        tuple("user.follow-events", "2"),
                        tuple("user.follow-request-events", "3"),
                        tuple("user.privacy-events", "4"));
        verify(outboxEventRepository).markPublished(eq(List.of(1L, 2L, 3L)), any(Instant.class));
    }

    /**
     * An instance older than the row it reads — mid-deploy, say — cannot know where it goes. Left
     * pending, a newer instance sends it; guessed, it would land on a topic whose consumer misreads it.
     */
    @Test
    void aRowWithAnUnknownAggregateType_staysPending() {
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(row(1L, "SOMETHING_NEW", "2")));

        publisher().publishPending();

        verifyNoInteractions(kafkaOperations);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    private static OutboxEvent row(Long id, String aggregateType, String aggregateId) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType("Whatever")
                .payload("{}")
                .build();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -pl services/user-service -am -Dtest='UserEventPublisherTest,OutboxPublisherTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `publishFollowRequested`, `publishPrivacyChanged` and the four-argument `publishFollowChanged`.

- [ ] **Step 3: Add the two new events**

Create `libs/event-schema/src/main/java/com/tiktok/event/user/UserPrivacyChangedEvent.java`:

```java
package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An account became private, or public again. video-service keeps its own copy of the flag and
 * re-announces the owner's videos with the audience that follows from it.
 *
 * <p>{@code occurredAt} is when the change was made, and the consumer compares it with the copy it
 * holds: a replay from the DLT that arrives after a newer change must not undo that change.
 */
public record UserPrivacyChangedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        boolean privateAccount
) implements DomainEvent {

    public static UserPrivacyChangedEvent of(Long userId, boolean privateAccount) {
        return new UserPrivacyChangedEvent(UUID.randomUUID().toString(), Instant.now(), userId, privateAccount);
    }
}
```

Create `libs/event-schema/src/main/java/com/tiktok/event/user/FollowRequestedEvent.java`:

```java
package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone asked to follow a private account. Not a follow: until the owner approves there is no
 * edge, and the approval arrives as a {@link UserFollowChangedEvent} with {@code requestApproved}.
 * A withdrawn or rejected request is not announced.
 */
public record FollowRequestedEvent(
        String eventId,
        Instant occurredAt,
        Long requesterId,
        Long targetId
) implements DomainEvent {

    public static FollowRequestedEvent of(Long requesterId, Long targetId) {
        return new FollowRequestedEvent(UUID.randomUUID().toString(), Instant.now(), requesterId, targetId);
    }
}
```

- [ ] **Step 4: Add `requestApproved` to `UserFollowChangedEvent`**

Replace the whole of `libs/event-schema/src/main/java/com/tiktok/event/user/UserFollowChangedEvent.java`:

```java
package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A follow edge was created or removed. Both ids' counts moved: followerId's followingCount,
 * followingId's followerCount — consumers that only care about one side still read both fields,
 * same shape as VideoLikeEvent carrying the liker rather than two separate topics.
 */
public record UserFollowChangedEvent(
        String eventId,
        Instant occurredAt,
        Long followerId,
        Long followingId,
        boolean followed,
        /*
         * The edge is a follow request its owner just approved, so the one to tell is the requester,
         * not the owner. Absent from every event produced before private accounts existed, which
         * deserialises to false.
         */
        boolean requestApproved
) implements DomainEvent {

    public static UserFollowChangedEvent of(Long followerId, Long followingId, boolean followed) {
        return of(followerId, followingId, followed, false);
    }

    public static UserFollowChangedEvent of(Long followerId, Long followingId, boolean followed,
                                            boolean requestApproved) {
        return new UserFollowChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                followerId, followingId, followed, requestApproved);
    }
}
```

- [ ] **Step 5: Queue each announcement under its own aggregate type**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/event/producer/UserEventPublisher.java`:

```java
package com.tiktok.userservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.DomainEvent;
import com.tiktok.event.user.FollowRequestedEvent;
import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.event.user.UserPrivacyChangedEvent;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

/**
 * Writes user-service's announcements to the outbox in the caller's transaction; {@link OutboxPublisher}
 * sends them to Kafka afterwards, each aggregate type to its own topic.
 *
 * <p>It used to send straight to the broker and block on the ack, inside the {@code @Transactional}
 * follow. That held a pooled connection for the whole round trip, and a broker that was down failed
 * a follow the database had already accepted — an event lost or a request lost, depending on which
 * side of the commit the failure landed. A row in the same transaction has neither problem: it
 * commits with the edge, and a broker outage only delays delivery.
 */
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    static final String FOLLOW_AGGREGATE = "USER_FOLLOW";
    static final String FOLLOW_REQUEST_AGGREGATE = "FOLLOW_REQUEST";
    static final String PRIVACY_AGGREGATE = "USER_PRIVACY";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishFollowChanged(Long followerId, Long followingId, boolean followed) {
        publishFollowChanged(followerId, followingId, followed, false);
    }

    /** @param requestApproved the edge is a follow request its owner approved — the requester is told */
    public void publishFollowChanged(Long followerId, Long followingId, boolean followed,
                                     boolean requestApproved) {
        // Keyed by the followed user, so every change to one user's follower count keeps its order
        // on a single partition.
        save(FOLLOW_AGGREGATE, followingId,
                UserFollowChangedEvent.of(followerId, followingId, followed, requestApproved));
    }

    public void publishFollowRequested(Long requesterId, Long targetId) {
        save(FOLLOW_REQUEST_AGGREGATE, targetId, FollowRequestedEvent.of(requesterId, targetId));
    }

    public void publishPrivacyChanged(Long userId, boolean privateAccount) {
        // Keyed by the account, so a quick private-public-private reaches video-service in order.
        save(PRIVACY_AGGREGATE, userId, UserPrivacyChangedEvent.of(userId, privateAccount));
    }

    @SneakyThrows
    private void save(String aggregateType, Long key, DomainEvent event) {
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(String.valueOf(key))
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build());
    }
}
```

- [ ] **Step 6: Route by aggregate type**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/event/producer/OutboxPublisher.java`:

```java
package com.tiktok.userservice.event.producer;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drains the outbox to Kafka, one topic per aggregate type. Marking is delegated to
 * {@link OutboxDispatcher} so a row is only marked published once the broker acknowledges it — see
 * that class for why doing it inline loses events.
 *
 * <p>Each topic carries one event type, so no eventType header: no consumer has anything to route.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Map<String, String> TOPICS = Map.of(
            UserEventPublisher.FOLLOW_AGGREGATE, "user.follow-events",
            UserEventPublisher.FOLLOW_REQUEST_AGGREGATE, "user.follow-request-events",
            UserEventPublisher.PRIVACY_AGGREGATE, "user.privacy-events");

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxDispatcher outboxDispatcher;

    /**
     * Not {@code @Transactional}: the dispatcher waits up to the ack timeout for the broker, and a
     * transaction around that wait held a pooled connection for all of it whenever Kafka was slow
     * or down. The acknowledged rows are marked afterwards in one short update of their own.
     */
    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        List<Long> acked = new ArrayList<>(pending.size());
        int published = outboxDispatcher.dispatch(pending, this::toRecord, event -> acked.add(event.getId()));
        if (!acked.isEmpty()) {
            outboxEventRepository.markPublished(acked, Instant.now());
        }

        if (published < pending.size()) {
            log.warn("Published {}/{} user outbox events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        String topic = TOPICS.get(event.getAggregateType());
        if (topic == null) {
            // Thrown, not guessed: the dispatcher leaves the row pending for an instance that knows
            // the type — the normal state of affairs halfway through a rolling deploy.
            throw new IllegalStateException("No topic for outbox aggregate type " + event.getAggregateType());
        }
        return new ProducerRecord<>(topic, event.getAggregateId(), event.getPayload());
    }
}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -pl services/user-service -am -Dtest='UserEventPublisherTest,OutboxPublisherTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 8: Check the other consumers of `UserFollowChangedEvent` still compile**

Run: `./mvnw -q test-compile -pl services/notification-service,services/chat-service -am`
Expected: BUILD SUCCESS. Both use only the three-argument `of(...)`, or read the payload as a `JsonNode`.

- [ ] **Step 9: Run user-service's full suite**

Run: `./mvnw test -pl services/user-service -am`
Expected: BUILD SUCCESS. `FollowServiceImplTest.follow_writesTheEventToTheOutboxInsteadOfSendingItInline` still passes: the payload still contains `"followerId":1` and `"followed":true`.

- [ ] **Step 10: Commit**

```bash
git add libs/event-schema/src/main/java/com/tiktok/event/user/UserPrivacyChangedEvent.java \
        libs/event-schema/src/main/java/com/tiktok/event/user/FollowRequestedEvent.java \
        libs/event-schema/src/main/java/com/tiktok/event/user/UserFollowChangedEvent.java \
        services/user-service/src/main/java/com/tiktok/userservice/event/producer/UserEventPublisher.java \
        services/user-service/src/main/java/com/tiktok/userservice/event/producer/OutboxPublisher.java \
        services/user-service/src/test/java/com/tiktok/userservice/event/producer/UserEventPublisherTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/event/producer/OutboxPublisherTest.java
git commit -m "$(cat <<'EOF'
feat(user): announce follow requests and privacy changes on topics of their own

The outbox now routes each row by aggregate type, so user.follow-events keeps
carrying one event type and no consumer has to route on a header. Adds
UserPrivacyChangedEvent and FollowRequestedEvent, and requestApproved on
UserFollowChangedEvent (absent on old payloads, read as false).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `privateAccount` on every profile

**Files:**
- Create: `services/user-service/src/main/resources/db/migration/V11__add_private_account_to_user_profiles.sql`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/entity/UserProfile.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/repository/UserProfileRepository.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/dto/response/UserProfileResponse.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/mapper/UserProfileMapperTest.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/UserProfileServiceImplTest.java`
- Mechanical (constructor arity only):
  - `services/user-service/src/test/java/com/tiktok/userservice/controller/BlockMuteControllerTest.java`
  - `services/user-service/src/test/java/com/tiktok/userservice/controller/UserProfileControllerTest.java`
  - `services/user-service/src/test/java/com/tiktok/userservice/service/AvatarUploadServiceTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `UserProfile.isPrivateAccount()`, with builder field `privateAccount`.
  - `int UserProfileRepository.updatePrivateAccount(Long userId, boolean privateAccount)`. It returns 1 when the flag changed, and 0 when the flag already had that value or there is no profile.
  - `UserProfileResponse` gains a last component, `boolean privateAccount`.

- [ ] **Step 1: Write the failing tests**

Append to `services/user-service/src/test/java/com/tiktok/userservice/mapper/UserProfileMapperTest.java`, inside the class:

```java
    @Test
    void toResponse_carriesThePrivateAccountFlag() {
        UserProfile profile = UserProfile.builder()
                .userId(42L)
                .displayName("Alice")
                .privateAccount(true)
                .build();

        assertThat(mapper.toResponse(profile).privateAccount()).isTrue();
    }
```

Append to `services/user-service/src/test/java/com/tiktok/userservice/service/UserProfileServiceImplTest.java`, inside the class:

```java
    @Test
    @Transactional
    void privateAccount_startsFalse_andFollowsTheColumn() {
        userProfileService.createFromRegisteredEvent(70L, "vera", null);
        assertThat(userProfileService.getByUserId(70L, 70L).privateAccount()).isFalse();
        long versionBefore = userProfileRepository.findByUserIdAndDeletedAtIsNull(70L).orElseThrow().getVersion();

        assertThat(userProfileRepository.updatePrivateAccount(70L, true)).isEqualTo(1);

        assertThat(userProfileService.getByUserId(70L, 70L).privateAccount()).isTrue();
        // The version moves with the flag, so an edit of the same profile that read it before the
        // switch fails on its version instead of writing the old value back.
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(70L).orElseThrow().getVersion())
                .isGreaterThan(versionBefore);
        // Conditional on the stored value: a repeat changes nothing and says so, which is what
        // keeps two racing PATCHes down to one announcement.
        assertThat(userProfileRepository.updatePrivateAccount(70L, true)).isZero();
        assertThat(userProfileRepository.updatePrivateAccount(999L, true)).isZero();
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -pl services/user-service -am -Dtest='UserProfileMapperTest,UserProfileServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `privateAccount` and `updatePrivateAccount`.

- [ ] **Step 3: Add the column**

Create `services/user-service/src/main/resources/db/migration/V11__add_private_account_to_user_profiles.sql`:

```sql
-- Only approved followers see a private account's videos, lists and reposts. On the profile rather
-- than on user_settings (V10) because every viewer needs it — the profile header, the Follow
-- button — and it rides the profile reads that already happen. Switched through the settings API
-- all the same, where the switch lives.
--
-- DEFAULT FALSE leaves every existing account public, which is what they all are: nothing could
-- make an account private before this column existed.
ALTER TABLE user_profiles ADD COLUMN private_account BOOLEAN NOT NULL DEFAULT FALSE;
```

- [ ] **Step 4: Map it**

In `services/user-service/src/main/java/com/tiktok/userservice/entity/UserProfile.java`, add after the `followingCount` field:

```java
    /**
     * Only approved followers see this account's videos, lists and reposts. Switched only by
     * UserProfileRepository.updatePrivateAccount, which also moves the version — see there.
     */
    @Builder.Default
    @Column(name = "private_account", nullable = false)
    private boolean privateAccount = false;
```

In `services/user-service/src/main/java/com/tiktok/userservice/repository/UserProfileRepository.java`, add after `replaceProviderAvatar`:

```java
    /**
     * Sets the flag only where it differs, so the row count says whether anything changed: two
     * PATCHes racing to the same value update one row between them, and only that one announces.
     *
     * <p>The version moves too. Hibernate writes every column of a dirty profile, so a
     * {@code PATCH /me} that read the row before this and flushed after would otherwise write the
     * old flag straight back — silently, since the version it checks would still match.
     *
     * @return 1 when the flag changed, 0 when it already had that value or there is no profile
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.privateAccount = :privateAccount, p.version = p.version + 1 "
            + "where p.userId = :userId and p.deletedAt is null and p.privateAccount <> :privateAccount")
    int updatePrivateAccount(@Param("userId") Long userId, @Param("privateAccount") boolean privateAccount);
```

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/dto/response/UserProfileResponse.java`:

```java
package com.tiktok.userservice.dto.response;

public record UserProfileResponse(
        Long userId,
        /**
         * The account's handle. Null for accounts created before user-service started copying it
         * off the registration event — a client showing it has to cope with its absence.
         */
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        long followerCount,
        long followingCount,
        /**
         * Only approved followers see this account's videos, followers/following lists and
         * reposts. The profile itself and its counters stay public.
         */
        boolean privateAccount
) {
}
```

`UserProfileMapper` needs no change: MapStruct maps `privateAccount` from `isPrivateAccount()`.

- [ ] **Step 5: Give every test constructor of `UserProfileResponse` its new argument**

The regex matches a constructor call whose arguments hold at most one level of parentheses, which is every site here (11 of them).

Run:
```bash
perl -0pi -e 's/new UserProfileResponse\(((?:[^()]++|\([^()]*\))*)\)/new UserProfileResponse($1, false)/g' \
  services/user-service/src/test/java/com/tiktok/userservice/controller/BlockMuteControllerTest.java \
  services/user-service/src/test/java/com/tiktok/userservice/controller/UserProfileControllerTest.java \
  services/user-service/src/test/java/com/tiktok/userservice/service/AvatarUploadServiceTest.java
grep -rn "new UserProfileResponse(" services/user-service/src/test
```
Expected: every printed call ends in `, false)`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw test -pl services/user-service -am -Dtest='UserProfileMapperTest,UserProfileServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Run user-service's full suite**

Run: `./mvnw test -pl services/user-service -am`
Expected: BUILD SUCCESS. Flyway applies V11, and `ddl-auto: validate` accepts the new mapping.

- [ ] **Step 8: Commit**

```bash
git add services/user-service/src/main/resources/db/migration/V11__add_private_account_to_user_profiles.sql \
        services/user-service/src/main/java/com/tiktok/userservice/entity/UserProfile.java \
        services/user-service/src/main/java/com/tiktok/userservice/repository/UserProfileRepository.java \
        services/user-service/src/main/java/com/tiktok/userservice/dto/response/UserProfileResponse.java \
        services/user-service/src/test/java/com/tiktok/userservice/mapper/UserProfileMapperTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/service/UserProfileServiceImplTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/controller/BlockMuteControllerTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/controller/UserProfileControllerTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/service/AvatarUploadServiceTest.java
git commit -m "$(cat <<'EOF'
feat(user): carry privateAccount on every profile

On user_profiles rather than user_settings because every viewer reads it,
riding the profile reads that already happen. The only writer is a
conditional update that also moves the version, so racing PATCHes announce
one change and a concurrent profile edit cannot write the old value back.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Follow requests

**Files:**
- Create: `services/user-service/src/main/resources/db/migration/V12__create_user_follow_requests_table.sql`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/entity/FollowRequest.java`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/repository/FollowRequestRepository.java`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/exception/AlreadyRequestedException.java`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/exception/FollowRequestNotFoundException.java`
- Create: `services/user-service/src/main/java/com/tiktok/userservice/controller/FollowRequestController.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/dto/response/FollowResponse.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/service/FollowService.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/service/FollowServiceImpl.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/service/BlockServiceImpl.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/controller/FollowController.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/FollowServiceImplTest.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/DuplicateRelationshipRaceTest.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/BlockMuteServiceImplTest.java`
- Test (new): `services/user-service/src/test/java/com/tiktok/userservice/controller/FollowRequestControllerTest.java`
- Test (one line): `services/user-service/src/test/java/com/tiktok/userservice/controller/UserProfileControllerTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `UserEventPublisher.publishFollowChanged(Long, Long, boolean, boolean)` and `publishFollowRequested(Long, Long)`.
  - Task 2: `UserProfile.isPrivateAccount()`, `UserProfileRepository.updatePrivateAccount(Long, boolean)`, and the 8-argument `UserProfileResponse`.
- Produces:
  - `record FollowResponse(Long followerId, Long followingId, FollowResponse.Status status)`, with `enum Status { FOLLOWING, REQUESTED }`.
  - `FollowRequestRepository` queries:
    - `Optional<FollowRequest> findByRequesterIdAndTargetIdAndDeletedAtIsNull(Long requesterId, Long targetId)`
    - `Page<FollowRequest> findByTargetIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long targetId, Pageable pageable)`
    - `List<FollowRequest> findByTargetIdAndDeletedAtIsNull(Long targetId)`
  - `FollowService` additions:
    - `Page<UserProfileResponse> listFollowRequests(Long ownerId, Pageable pageable)`
    - `FollowResponse approveFollowRequest(Long ownerId, Long requesterId)`
    - `void rejectFollowRequest(Long ownerId, Long requesterId)`
    - `void approveAllFollowRequests(Long ownerId)`
  - Exceptions `AlreadyRequestedException()` and `FollowRequestNotFoundException()`.
  - Endpoints under `/api/v1/users/me/follow-requests`:
    - `GET` (the list)
    - `POST /{requesterId}/approve`
    - `DELETE /{requesterId}` (reject)

- [ ] **Step 1: Write the failing service tests**

In `services/user-service/src/test/java/com/tiktok/userservice/service/FollowServiceImplTest.java`:

Add these imports:

```java
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.exception.AlreadyRequestedException;
import com.tiktok.userservice.exception.FollowRequestNotFoundException;
import com.tiktok.userservice.repository.FollowRequestRepository;
```

Add the field:

```java
    @Autowired
    private FollowRequestRepository followRequestRepository;
```

In `setUp()`, add `followRequestRepository.deleteAll();` right after `outboxEventRepository.deleteAll();`.

Append the helper and the tests, inside the class:

```java
    /** Straight on the column: the settings path is Task 4's, and going public there approves requests. */
    private void makePrivate(Long userId) {
        userProfileRepository.updatePrivateAccount(userId, true);
    }

    @Test
    @Transactional
    void follow_privateAccount_createsARequestNotAnEdge() {
        makePrivate(2L);

        FollowResponse response = followService.follow(1L, 2L);

        assertThat(response.status()).isEqualTo(FollowResponse.Status.REQUESTED);
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isZero();
        assertThat(outboxEventRepository.findAll())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("FollowRequestedEvent");
                    // Keyed by the account asked, so one account's requests keep their order.
                    assertThat(event.getAggregateId()).isEqualTo("2");
                    assertThat(event.getPayload()).contains("\"requesterId\":1", "\"targetId\":2");
                });
    }

    @Test
    @Transactional
    void follow_privateAccountTwice_isAlreadyRequested() {
        makePrivate(2L);
        followService.follow(1L, 2L);

        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyRequestedException.class);
    }

    /** Going private keeps existing followers, so an edge already there still answers first. */
    @Test
    @Transactional
    void follow_privateAccountAlreadyFollowed_isAlreadyFollowing() {
        followService.follow(1L, 2L);
        makePrivate(2L);

        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyFollowingException.class);
    }

    /** The Follow button reads "Requested", and pressing it again withdraws — silently. */
    @Test
    @Transactional
    void unfollow_withAPendingRequest_withdrawsIt() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        outboxEventRepository.deleteAll();

        followService.unfollow(1L, 2L);

        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @Transactional
    void approve_turnsTheRequestIntoAFollowAndSaysSo() {
        makePrivate(2L);
        followService.follow(1L, 2L);

        FollowResponse response = followService.approveFollowRequest(2L, 1L);

        assertThat(response.status()).isEqualTo(FollowResponse.Status.FOLLOWING);
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isPresent();
        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isEqualTo(1);
        assertThat(userProfileService.getByUserId(1L, 1L).followingCount()).isEqualTo(1);
        assertThat(outboxEventRepository.findAll())
                .filteredOn(event -> "UserFollowChangedEvent".equals(event.getEventType()))
                .singleElement()
                .extracting(OutboxEvent::getPayload, as(org.assertj.core.api.InstanceOfAssertFactories.STRING))
                .contains("\"followed\":true", "\"requestApproved\":true");
    }

    @Test
    @Transactional
    void approveAndReject_withNoPendingRequest_areNotFound() {
        assertThatThrownBy(() -> followService.approveFollowRequest(2L, 1L))
                .isInstanceOf(FollowRequestNotFoundException.class);
        assertThatThrownBy(() -> followService.rejectFollowRequest(2L, 1L))
                .isInstanceOf(FollowRequestNotFoundException.class);
    }

    /** A rejection is silent: no edge, no counter, nothing announced. */
    @Test
    @Transactional
    void reject_endsTheRequestWithoutAFollowOrAnAnnouncement() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        outboxEventRepository.deleteAll();

        followService.rejectFollowRequest(2L, 1L);

        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    /** The unique index covers live rows only, so a rejected requester can ask again. */
    @Test
    @Transactional
    void reject_thenAskingAgain_isANewRequest() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        followService.rejectFollowRequest(2L, 1L);

        assertThat(followService.follow(1L, 2L).status()).isEqualTo(FollowResponse.Status.REQUESTED);
        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isPresent();
    }

    @Test
    @Transactional
    void listFollowRequests_newestFirst() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        makePrivate(2L);
        followService.follow(1L, 2L);
        followService.follow(3L, 2L);

        assertThat(followService.listFollowRequests(2L, PageRequest.of(0, 10)).getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactly(3L, 1L);
    }

    /**
     * More than one request is the case that matters: each edge moves the counters through bulk
     * updates that clear the persistence context, and a request not yet ended by then is detached
     * and its end silently lost.
     */
    @Test
    @Transactional
    void approveAll_approvesEveryPendingRequest() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        makePrivate(2L);
        followService.follow(1L, 2L);
        followService.follow(3L, 2L);

        followService.approveAllFollowRequests(2L);

        assertThat(followRequestRepository.findByTargetIdAndDeletedAtIsNull(2L)).isEmpty();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isPresent();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(3L, 2L)).isPresent();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isEqualTo(2);
    }

    /**
     * A request left pending across a switch to public, then a direct follow: approving the
     * request later must end it without a second edge or a second count.
     */
    @Test
    @Transactional
    void approve_whenTheFollowAlreadyExists_onlyEndsTheRequest() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        userProfileRepository.updatePrivateAccount(2L, false);
        followService.follow(1L, 2L);

        followService.approveFollowRequest(2L, 1L);

        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isEqualTo(1);
    }
```

- [ ] **Step 2: Write the failing race, block and controller tests**

In `services/user-service/src/test/java/com/tiktok/userservice/service/DuplicateRelationshipRaceTest.java`:

Add these imports:

```java
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.exception.AlreadyRequestedException;
import com.tiktok.userservice.repository.FollowRequestRepository;
```

Add the field next to the other spies:

```java
    @SpyBean
    private FollowRequestRepository followRequestRepository;
```

In `setUp()`, add `followRequestRepository.deleteAll();` right after `userMuteRepository.deleteAll();`.

Append this test:

```java
    /** Same race as the follow edge, same backstop: uq_user_follow_requests_requester_target. */
    @Test
    void followRequest_losingADuplicateRace_reports409NotAServerError() {
        userProfileRepository.save(UserProfile.builder()
                .userId(3L)
                .displayName("carol")
                .privateAccount(true)
                .build());
        followService.follow(1L, 3L);
        doReturn(Optional.empty())
                .when(followRequestRepository)
                .findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 3L);

        assertThatThrownBy(() -> followService.follow(1L, 3L))
                .isInstanceOf(AlreadyRequestedException.class);
    }
```

In `services/user-service/src/test/java/com/tiktok/userservice/service/BlockMuteServiceImplTest.java`:

Add the import `import com.tiktok.userservice.repository.FollowRequestRepository;`.

Add `followRequestRepository.deleteAll();` as the first line of `setUp()`.

Append this, inside the class:

```java
    @Autowired
    private FollowRequestRepository followRequestRepository;

    /** Pending requests go with the edges, both ways — approving one later would follow across the block. */
    @Test
    @Transactional
    void block_endsPendingFollowRequestsBothWays() {
        userProfileRepository.updatePrivateAccount(1L, true);
        userProfileRepository.updatePrivateAccount(2L, true);
        followService.follow(1L, 2L);
        followService.follow(2L, 1L);

        blockService.block(1L, 2L);

        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(2L, 1L)).isEmpty();
    }
```

Create `services/user-service/src/test/java/com/tiktok/userservice/controller/FollowRequestControllerTest.java`:

```java
package com.tiktok.userservice.controller;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.PageableConfig;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.AlreadyRequestedException;
import com.tiktok.userservice.exception.FollowRequestNotFoundException;
import com.tiktok.userservice.service.FollowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {FollowRequestController.class, FollowController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, PageableConfig.class})
@TestPropertySource(properties = "jwt.secret=test-secret-at-least-32-bytes-long-0123456789")
class FollowRequestControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FollowService followService;

    private String tokenFor(long userId) {
        return new JwtProvider(JWT_SECRET).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    /** 201 either way; the status is how the client tells Following from Requested. */
    @Test
    void follow_ofAPrivateAccount_is201Requested() throws Exception {
        when(followService.follow(1L, 2L))
                .thenReturn(new FollowResponse(1L, 2L, FollowResponse.Status.REQUESTED));

        mockMvc.perform(post("/api/v1/users/2/follow").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    void follow_withARequestPending_is409AlreadyRequested() throws Exception {
        when(followService.follow(1L, 2L)).thenThrow(new AlreadyRequestedException());

        mockMvc.perform(post("/api/v1/users/2/follow").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REQUESTED"));
    }

    @Test
    void list_isTheCallersOwnRequests() throws Exception {
        UserProfileResponse requester = new UserProfileResponse(7L, "carol", "Carol", null, null, 0, 0, false);
        when(followService.listFollowRequests(eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(requester), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/users/me/follow-requests").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(7));
    }

    @Test
    void approve_answersTheNewFollow() throws Exception {
        when(followService.approveFollowRequest(1L, 7L))
                .thenReturn(new FollowResponse(7L, 1L, FollowResponse.Status.FOLLOWING));

        mockMvc.perform(post("/api/v1/users/me/follow-requests/7/approve")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followerId").value(7))
                .andExpect(jsonPath("$.data.status").value("FOLLOWING"));
    }

    @Test
    void approve_withNothingPending_is404() throws Exception {
        when(followService.approveFollowRequest(1L, 7L)).thenThrow(new FollowRequestNotFoundException());

        mockMvc.perform(post("/api/v1/users/me/follow-requests/7/approve")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FOLLOW_REQUEST_NOT_FOUND"));
    }

    @Test
    void reject_actsOnTheCallersOwnRequest() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me/follow-requests/7")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk());

        verify(followService).rejectFollowRequest(1L, 7L);
    }
}
```

In `services/user-service/src/test/java/com/tiktok/userservice/controller/UserProfileControllerTest.java`, change:

```java
        when(followService.follow(1L, 2L)).thenReturn(new FollowResponse(1L, 2L));
```

to:

```java
        when(followService.follow(1L, 2L)).thenReturn(new FollowResponse(1L, 2L, FollowResponse.Status.FOLLOWING));
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -pl services/user-service -am -Dtest='FollowServiceImplTest,DuplicateRelationshipRaceTest,BlockMuteServiceImplTest,FollowRequestControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `FollowRequestRepository`, `AlreadyRequestedException`, `FollowResponse.Status` and the new `FollowService` methods.

- [ ] **Step 4: Add the table**

Create `services/user-service/src/main/resources/db/migration/V12__create_user_follow_requests_table.sql`:

```sql
-- A follow of a private account, waiting for its owner. Same shape as user_follows (V2): Snowflake
-- id, audit columns, soft delete. Approving, rejecting, withdrawing and blocking all end a request
-- by setting deleted_at, and the unique index covers live rows only, so asking again after a
-- rejection is a new request rather than a conflict.
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
-- The owner's requests list, newest first.
CREATE INDEX idx_user_follow_requests_target_created
    ON user_follow_requests (target_id, created_at DESC) WHERE deleted_at IS NULL;
```

- [ ] **Step 5: Add the entity, repository and exceptions**

Create `services/user-service/src/main/java/com/tiktok/userservice/entity/FollowRequest.java`:

```java
package com.tiktok.userservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A follow of a private account, waiting for its owner. Not an edge: nothing that reads
 * user_follows sees it, so every existing check keeps meaning what it meant.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_follow_requests")
public class FollowRequest extends BaseEntity {

    @Column(name = "requester_id", nullable = false)
    private Long requesterId;

    @Column(name = "target_id", nullable = false)
    private Long targetId;
}
```

Create `services/user-service/src/main/java/com/tiktok/userservice/repository/FollowRequestRepository.java`:

```java
package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.FollowRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FollowRequestRepository extends JpaRepository<FollowRequest, Long> {

    Optional<FollowRequest> findByRequesterIdAndTargetIdAndDeletedAtIsNull(Long requesterId, Long targetId);

    /** The owner's list, newest first; the Snowflake id breaks a tie inside one microsecond. */
    Page<FollowRequest> findByTargetIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long targetId, Pageable pageable);

    /** Every pending request of an account going public. */
    List<FollowRequest> findByTargetIdAndDeletedAtIsNull(Long targetId);
}
```

Create `services/user-service/src/main/java/com/tiktok/userservice/exception/AlreadyRequestedException.java`:

```java
package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class AlreadyRequestedException extends ConflictException {

    public AlreadyRequestedException() {
        super("ALREADY_REQUESTED", "A follow request to this user is already pending");
    }
}
```

Create `services/user-service/src/main/java/com/tiktok/userservice/exception/FollowRequestNotFoundException.java`:

```java
package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class FollowRequestNotFoundException extends ResourceNotFoundException {

    public FollowRequestNotFoundException() {
        super("FOLLOW_REQUEST_NOT_FOUND", "No pending follow request from this user");
    }
}
```

- [ ] **Step 6: Give `FollowResponse` a status**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/dto/response/FollowResponse.java`:

```java
package com.tiktok.userservice.dto.response;

public record FollowResponse(
        Long followerId,
        Long followingId,
        /** FOLLOWING when the edge exists now; REQUESTED when a private account's owner has to approve. */
        Status status
) {

    public enum Status {
        FOLLOWING,
        REQUESTED
    }
}
```

- [ ] **Step 7: Widen `FollowService`**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/service/FollowService.java`:

```java
package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FollowService {

    /** A follow, or a follow request when the account is private — the status says which. */
    FollowResponse follow(Long followerId, Long followingId);

    /** Removes the edge, or withdraws the pending request when there is no edge. */
    void unfollow(Long followerId, Long followingId);

    /** Whether {@code viewerId} and {@code otherUserId} follow each other (mutual = "friends"). */
    FriendshipResponse friendship(Long viewerId, Long otherUserId);

    Page<UserProfileResponse> listFollowers(Long viewerId, Long userId, Pageable pageable);

    Page<UserProfileResponse> listFollowing(Long viewerId, Long userId, Pageable pageable);

    /** The owner's pending requests, newest first, as the requesters' profiles. */
    Page<UserProfileResponse> listFollowRequests(Long ownerId, Pageable pageable);

    FollowResponse approveFollowRequest(Long ownerId, Long requesterId);

    /** Silent: the requester is not told. */
    void rejectFollowRequest(Long ownerId, Long requesterId);

    /** Every pending request of an account going public, in the caller's transaction. */
    void approveAllFollowRequests(Long ownerId);
}
```

- [ ] **Step 8: Branch the follow and add the owner's side**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/service/FollowServiceImpl.java`:

```java
package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.FollowRequest;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.AlreadyRequestedException;
import com.tiktok.userservice.exception.CannotFollowBlockedUserException;
import com.tiktok.userservice.exception.CannotFollowSelfException;
import com.tiktok.userservice.exception.FollowRequestNotFoundException;
import com.tiktok.userservice.exception.NotFollowingException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final FollowRequestRepository followRequestRepository;
    private final UserProfileBatchAssembler profileBatchAssembler;
    private final ProfileVisibilityGuard profileVisibilityGuard;
    private final UserEventPublisher userEventPublisher;

    @Override
    @Transactional
    public FollowResponse follow(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new CannotFollowSelfException();
        }

        // No FK backs user_follows.following_id, and the counter updates are UPDATE ... WHERE
        // that silently match zero rows — without this an edge to a non-existent user is
        // accepted with a 200 and leaves followingCount permanently overstated.
        UserProfile target = userProfileRepository.findByUserIdAndDeletedAtIsNull(followingId)
                .orElseThrow(() -> new UserProfileNotFoundException(followingId));

        // The follower is checked for the same reason as the target, on the other side of the
        // edge. A caller whose UserRegisteredEvent never landed holds a valid token but no
        // profile row; incrementFollowingCount would match nothing and the edge would put an id
        // with no profile into the target's followers list forever.
        if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(followerId)) {
            throw new UserProfileNotFoundException(followerId);
        }

        if (userBlockRepository.existsBlockBetween(followerId, followingId)) {
            throw new CannotFollowBlockedUserException();
        }

        // Before the private branch: going private keeps existing followers, so an edge that is
        // already there answers the same way on either kind of account.
        if (hasEdge(followerId, followingId)) {
            throw new AlreadyFollowingException();
        }

        return target.isPrivateAccount()
                ? request(followerId, followingId)
                : addEdge(followerId, followingId, false);
    }

    @Override
    @Transactional
    public void unfollow(Long followerId, Long followingId) {
        Optional<UserFollow> follow =
                userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId);
        if (follow.isPresent()) {
            follow.get().markDeleted();
            userProfileRepository.decrementFollowingCount(followerId);
            userProfileRepository.decrementFollowerCount(followingId);
            userEventPublisher.publishFollowChanged(followerId, followingId, false);
            return;
        }

        // No edge: on a private account the button reads "Requested", and pressing it again
        // withdraws the request. Nothing is announced — no follow ever existed.
        followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(followerId, followingId)
                .orElseThrow(NotFollowingException::new)
                .markDeleted();
    }

    @Override
    @Transactional(readOnly = true)
    public FriendshipResponse friendship(Long viewerId, Long otherUserId) {
        // Read-only and edge-only: a non-existent user simply has no follow rows, so it answers
        // false rather than 404 — the caller (video-service's FRIENDS visibility check) only ever
        // needs the boolean, and self is never a friend.
        boolean friends = !viewerId.equals(otherUserId)
                && hasEdge(viewerId, otherUserId)
                && hasEdge(otherUserId, viewerId);
        return new FriendshipResponse(otherUserId, friends);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowers(Long viewerId, Long userId, Pageable pageable) {
        profileVisibilityGuard.requireVisible(viewerId, userId);

        // Visible-to rather than the plain lookup: the guard above answers whether this list may be
        // read, the query answers who in it may be shown. Someone who blocked the viewer is neither.
        return profileBatchAssembler.toResponses(
                userFollowRepository.findFollowerIdsVisibleTo(viewerId, userId, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowing(Long viewerId, Long userId, Pageable pageable) {
        profileVisibilityGuard.requireVisible(viewerId, userId);

        return profileBatchAssembler.toResponses(
                userFollowRepository.findFollowingIdsVisibleTo(viewerId, userId, pageable));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowRequests(Long ownerId, Pageable pageable) {
        // No block filter: blocking ends the requests between the two accounts.
        return profileBatchAssembler.toResponses(followRequestRepository
                .findByTargetIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(ownerId, pageable)
                .map(FollowRequest::getRequesterId));
    }

    @Override
    @Transactional
    public FollowResponse approveFollowRequest(Long ownerId, Long requesterId) {
        FollowRequest request = followRequestRepository
                .findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, ownerId)
                .orElseThrow(FollowRequestNotFoundException::new);
        approve(List.of(request));
        return new FollowResponse(requesterId, ownerId, FollowResponse.Status.FOLLOWING);
    }

    @Override
    @Transactional
    public void rejectFollowRequest(Long ownerId, Long requesterId) {
        followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, ownerId)
                .orElseThrow(FollowRequestNotFoundException::new)
                .markDeleted();
    }

    /**
     * ponytail: one transaction for all of them. Fine at this scale; an account with thousands of
     * pending requests would want the approvals batched by a job instead.
     */
    @Override
    @Transactional
    public void approveAllFollowRequests(Long ownerId) {
        approve(followRequestRepository.findByTargetIdAndDeletedAtIsNull(ownerId));
    }

    /**
     * A follow of a private account: a request its owner answers, not an edge. Same race and same
     * guarantee as the edge in {@link #addEdge} — the check is the fast path,
     * uq_user_follow_requests_requester_target the guarantee.
     *
     * <p>ponytail: an owner going public between the profile read in {@link #follow} and this
     * insert leaves one pending request on a public account, because approveAllFollowRequests ran
     * before it existed. It stays answerable from the requests list; closing the gap needs a lock
     * on the profile row.
     */
    private FollowResponse request(Long requesterId, Long targetId) {
        if (followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, targetId).isPresent()) {
            throw new AlreadyRequestedException();
        }

        try {
            followRequestRepository.saveAndFlush(FollowRequest.builder()
                    .requesterId(requesterId)
                    .targetId(targetId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw new AlreadyRequestedException();
        }

        userEventPublisher.publishFollowRequested(requesterId, targetId);
        return new FollowResponse(requesterId, targetId, FollowResponse.Status.REQUESTED);
    }

    /** The edge, both counters and the announcement — one path for a follow and an approval. */
    private FollowResponse addEdge(Long followerId, Long followingId, boolean requestApproved) {
        // The check in follow() is not the guarantee — two concurrent requests both pass it and
        // only uq_user_follows_follower_following stops the second. saveAndFlush forces that
        // violation to surface here instead of at commit, so the loser is answered 409
        // ALREADY_FOLLOWING like the sequential case rather than a 500, and rolls back before the
        // counters move.
        try {
            userFollowRepository.saveAndFlush(UserFollow.builder()
                    .followerId(followerId)
                    .followingId(followingId)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            throw new AlreadyFollowingException();
        }

        userProfileRepository.incrementFollowingCount(followerId);
        userProfileRepository.incrementFollowerCount(followingId);
        userEventPublisher.publishFollowChanged(followerId, followingId, true, requestApproved);

        return new FollowResponse(followerId, followingId, FollowResponse.Status.FOLLOWING);
    }

    /**
     * Every request is ended before any edge is written. Each edge moves the counters through bulk
     * updates that clear the persistence context, and a request still waiting to be ended by then
     * would be detached, its end silently lost.
     */
    private void approve(List<FollowRequest> requests) {
        requests.forEach(FollowRequest::markDeleted);
        for (FollowRequest request : requests) {
            // The edge can already be there: a request left pending across a switch to public (see
            // request()), then a direct follow. Ending the request is all that is left to do.
            if (!hasEdge(request.getRequesterId(), request.getTargetId())) {
                addEdge(request.getRequesterId(), request.getTargetId(), true);
            }
        }
    }

    private boolean hasEdge(Long followerId, Long followingId) {
        return userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId)
                .isPresent();
    }
}
```

- [ ] **Step 9: End pending requests on block**

In `services/user-service/src/main/java/com/tiktok/userservice/service/BlockServiceImpl.java`:

Add the imports `import com.tiktok.userservice.entity.FollowRequest;` and `import com.tiktok.userservice.repository.FollowRequestRepository;`.

Add the field after `userFollowRepository`:

```java
    private final FollowRequestRepository followRequestRepository;
```

Replace:

```java
        removeFollowIfPresent(blockerId, blockedId);
        removeFollowIfPresent(blockedId, blockerId);
```

with:

```java
        removeFollowIfPresent(blockerId, blockedId);
        removeFollowIfPresent(blockedId, blockerId);
        // Pending requests go with the edges: a blocked account must not sit in the requests list,
        // and approving one would create a follow across the block.
        removeFollowRequestIfPresent(blockerId, blockedId);
        removeFollowRequestIfPresent(blockedId, blockerId);
```

Add after `removeFollowIfPresent`:

```java
    private void removeFollowRequestIfPresent(Long requesterId, Long targetId) {
        followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, targetId)
                .ifPresent(FollowRequest::markDeleted);
    }
```

- [ ] **Step 10: Add the owner's endpoints and say what follow now answers**

Create `services/user-service/src/main/java/com/tiktok/userservice/controller/FollowRequestController.java`:

```java
package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A private account's side of follow requests. The requester's side is POST/DELETE /{userId}/follow. */
@RestController
@RequestMapping("/api/v1/users/me/follow-requests")
@RequiredArgsConstructor
@Tag(name = "Follows", description = "The follow graph and what a viewer may see of it")
public class FollowRequestController {

    private final FollowService followService;

    @Operation(summary = "Pending follow requests to the caller",
            description = "Newest first. A request ends when it is approved, rejected or "
                    + "withdrawn, or when either side blocks the other.")
    @GetMapping
    public ApiResponse<Page<UserProfileResponse>> list(
            @AuthenticationPrincipal Long currentUserId,
            Pageable pageable) {
        return ApiResponse.success(followService.listFollowRequests(currentUserId, pageable));
    }

    @Operation(summary = "Approve a follow request",
            description = "The requester now follows the caller. 404 FOLLOW_REQUEST_NOT_FOUND "
                    + "when none is pending.")
    @PostMapping("/{requesterId}/approve")
    public ApiResponse<FollowResponse> approve(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long requesterId) {
        return ApiResponse.success(followService.approveFollowRequest(currentUserId, requesterId));
    }

    @Operation(summary = "Reject a follow request",
            description = "Silent: the requester is not told. 404 FOLLOW_REQUEST_NOT_FOUND when "
                    + "none is pending.")
    @DeleteMapping("/{requesterId}")
    public ApiResponse<Void> reject(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long requesterId) {
        followService.rejectFollowRequest(currentUserId, requesterId);
        return ApiResponse.success(null);
    }
}
```

In `services/user-service/src/main/java/com/tiktok/userservice/controller/FollowController.java`, replace:

```java
    @Operation(summary = "Follow a user",
            description = "400 CANNOT_FOLLOW_SELF, 404 USER_PROFILE_NOT_FOUND when either side "
                    + "has no profile, 409 ALREADY_FOLLOWING, 409 CANNOT_FOLLOW_BLOCKED_USER "
                    + "when a block exists in either direction.")
```

with:

```java
    @Operation(summary = "Follow a user",
            description = "201 either way: status FOLLOWING, or REQUESTED when the account is "
                    + "private and its owner has to approve. 400 CANNOT_FOLLOW_SELF, "
                    + "404 USER_PROFILE_NOT_FOUND when either side has no profile, 403 USER_BLOCKED "
                    + "when a block exists in either direction, 409 ALREADY_FOLLOWING, "
                    + "409 ALREADY_REQUESTED.")
```

Replace:

```java
    @Operation(summary = "Unfollow a user", description = "404 NOT_FOLLOWING when there is no edge.")
```

with:

```java
    @Operation(summary = "Unfollow a user",
            description = "Removes the edge, or withdraws a pending request when there is none. "
                    + "404 NOT_FOLLOWING when there is neither.")
```

- [ ] **Step 11: Run the tests to verify they pass**

Run: `./mvnw test -pl services/user-service -am -Dtest='FollowServiceImplTest,DuplicateRelationshipRaceTest,BlockMuteServiceImplTest,FollowRequestControllerTest,UserProfileControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 12: Run user-service's full suite**

Run: `./mvnw test -pl services/user-service -am`
Expected: BUILD SUCCESS. Flyway applies V12, and `ddl-auto: validate` accepts `FollowRequest`.

- [ ] **Step 13: Commit**

```bash
git add services/user-service/src/main/resources/db/migration/V12__create_user_follow_requests_table.sql \
        services/user-service/src/main/java/com/tiktok/userservice/entity/FollowRequest.java \
        services/user-service/src/main/java/com/tiktok/userservice/repository/FollowRequestRepository.java \
        services/user-service/src/main/java/com/tiktok/userservice/exception/AlreadyRequestedException.java \
        services/user-service/src/main/java/com/tiktok/userservice/exception/FollowRequestNotFoundException.java \
        services/user-service/src/main/java/com/tiktok/userservice/controller/FollowRequestController.java \
        services/user-service/src/main/java/com/tiktok/userservice/controller/FollowController.java \
        services/user-service/src/main/java/com/tiktok/userservice/dto/response/FollowResponse.java \
        services/user-service/src/main/java/com/tiktok/userservice/service/FollowService.java \
        services/user-service/src/main/java/com/tiktok/userservice/service/FollowServiceImpl.java \
        services/user-service/src/main/java/com/tiktok/userservice/service/BlockServiceImpl.java \
        services/user-service/src/test/java/com/tiktok/userservice/service/FollowServiceImplTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/service/DuplicateRelationshipRaceTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/service/BlockMuteServiceImplTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/controller/FollowRequestControllerTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/controller/UserProfileControllerTest.java
git commit -m "$(cat <<'EOF'
feat(user): follow requests for private accounts

Following a private account creates a request instead of an edge (201,
status REQUESTED; 409 ALREADY_REQUESTED on a repeat, backed by a partial
unique index). The owner lists, approves and rejects them under
/users/me/follow-requests; approving goes through the same edge path as a
follow and says requestApproved. DELETE /follow withdraws a pending request,
and a block ends requests both ways.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: The private account switch on the settings API

**Files:**
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/dto/request/UpdateUserSettingsRequest.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/dto/response/UserSettingsResponse.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/mapper/UserSettingsMapper.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/service/UserSettingsService.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/service/UserSettingsServiceImpl.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/controller/UserSettingsController.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/UserSettingsServiceImplTest.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/controller/UserSettingsControllerTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `UserEventPublisher.publishPrivacyChanged(Long, boolean)`.
  - Task 2: `UserProfileRepository.updatePrivateAccount(Long, boolean)` and `UserProfile.isPrivateAccount()`.
  - Task 3: `FollowService.approveAllFollowRequests(Long)`.
- Produces:
  - On `UpdateUserSettingsRequest`: `setPrivateAccount(Boolean)`, `getPrivateAccount()` and `isPrivateAccountSet()`.
  - `UserSettingsResponse` gains a last component, `boolean privateAccount`.
  - `UserSettingsMapper.toResponse(UserSettings settings, boolean privateAccount)` replaces the one-argument `toResponse`.

- [ ] **Step 1: Write the failing service tests**

In `services/user-service/src/test/java/com/tiktok/userservice/service/UserSettingsServiceImplTest.java`:

Add these imports:

```java
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.OutboxEventRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
```

Add these fields after `userSettingsRepository`:

```java
    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private FollowService followService;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;
```

Replace `setUp()`:

```java
    @BeforeEach
    void setUp() {
        userSettingsRepository.deleteAll();
        outboxEventRepository.deleteAll();
        followRequestRepository.deleteAll();
        userFollowRepository.deleteAll();
        userProfileRepository.deleteAll();
        // The outbox is drained on a schedule in tests too, and the dispatcher waits on the future
        // the template hands back.
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }
```

Append the helpers and the tests, inside the class:

```java
    private static UpdateUserSettingsRequest privacy(Boolean privateAccount) {
        UpdateUserSettingsRequest request = new UpdateUserSettingsRequest();
        request.setPrivateAccount(privateAccount);
        return request;
    }

    private List<OutboxEvent> privacyEvents() {
        return outboxEventRepository.findAll().stream()
                .filter(event -> "UserPrivacyChangedEvent".equals(event.getEventType()))
                .toList();
    }

    /** Off the profile, where every viewer reads it — and false before the profile exists. */
    @Test
    void read_answersPrivateAccountOffTheProfile_andFalseWithoutOne() {
        assertThat(userSettingsService.getOwnSettings(1L).privateAccount()).isFalse();

        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userSettingsService.updateOwnSettings(1L, privacy(true));

        assertThat(userSettingsService.getOwnSettings(1L).privateAccount()).isTrue();
    }

    /** A value equal to the stored one queues nothing, whatever else the same PATCH changes. */
    @Test
    void privacy_queuesOneEventPerChange_andNoneWhenUnchanged() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);

        assertThat(userSettingsService.updateOwnSettings(1L, privacy(true)).privateAccount()).isTrue();
        userSettingsService.updateOwnSettings(1L, privacy(true));
        UpdateUserSettingsRequest unrelated = new UpdateUserSettingsRequest();
        unrelated.setCommentAudience("FRIENDS");
        assertThat(userSettingsService.updateOwnSettings(1L, unrelated).privateAccount()).isTrue();

        assertThat(privacyEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getAggregateId()).isEqualTo("1");
                    assertThat(event.getPayload()).contains("\"privateAccount\":true");
                });
    }

    @Test
    void privacy_explicitNull_isRejected() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, privacy(null)))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("privateAccount");
    }

    /** No profile, nothing to put the flag on — and the settings row the same PATCH wrote goes too. */
    @Test
    void privacy_withNoProfile_isNotFound() {
        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, privacy(true)))
                .isInstanceOf(UserProfileNotFoundException.class);

        assertThat(userSettingsRepository.findByUserIdAndDeletedAtIsNull(1L)).isEmpty();
    }

    /** Nothing gates following any more, and these people already asked. */
    @Test
    void goingPublic_approvesEveryPendingRequest() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        userSettingsService.updateOwnSettings(1L, privacy(true));
        followService.follow(2L, 1L);
        followService.follow(3L, 1L);

        userSettingsService.updateOwnSettings(1L, privacy(false));

        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(2L, 1L)).isPresent();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(3L, 1L)).isPresent();
        assertThat(followRequestRepository.findByTargetIdAndDeletedAtIsNull(1L)).isEmpty();
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow().getFollowerCount())
                .isEqualTo(2);
    }

    /** "Your existing followers won't be affected." */
    @Test
    void goingPrivate_leavesExistingFollowersAlone() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        followService.follow(2L, 1L);

        userSettingsService.updateOwnSettings(1L, privacy(true));

        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(2L, 1L)).isPresent();
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow().getFollowerCount())
                .isEqualTo(1);
    }

    /** One transaction: a PATCH refused for any key leaves the switch, and its announcement, where they were. */
    @Test
    void privacy_inAPatchThatFailsValidation_changesNothing() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        UpdateUserSettingsRequest request = privacy(true);
        request.setSleepHoursStart(LocalTime.of(22, 0));

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, request))
                .isInstanceOf(InvalidUserSettingsException.class);

        assertThat(userSettingsService.getOwnSettings(1L).privateAccount()).isFalse();
        assertThat(privacyEvents()).isEmpty();
    }
```

- [ ] **Step 2: Write the failing controller test**

In `services/user-service/src/test/java/com/tiktok/userservice/controller/UserSettingsControllerTest.java`, change `DEFAULTS` to:

```java
    private static final UserSettingsResponse DEFAULTS = new UserSettingsResponse(
            SettingsAudience.EVERYONE, SettingsAudience.EVERYONE, null, null, null, true, false);
```

In `getOwnSettings_answersTheFlatKeyedObject`, change:

```java
                LocalTime.of(22, 30), LocalTime.of(7, 0), false));
```

to:

```java
                LocalTime.of(22, 30), LocalTime.of(7, 0), false, false));
```

Append inside the class:

```java
    @Test
    void patch_privateAccount_reachesTheServiceAndComesBack() throws Exception {
        when(userSettingsService.updateOwnSettings(eq(1L), any())).thenReturn(new UserSettingsResponse(
                SettingsAudience.EVERYONE, SettingsAudience.EVERYONE, null, null, null, true, true));

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"privateAccount": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.privateAccount").value(true));

        ArgumentCaptor<UpdateUserSettingsRequest> captor =
                ArgumentCaptor.forClass(UpdateUserSettingsRequest.class);
        verify(userSettingsService).updateOwnSettings(eq(1L), captor.capture());
        assertThat(captor.getValue().isPrivateAccountSet()).isTrue();
        assertThat(captor.getValue().getPrivateAccount()).isTrue();
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -pl services/user-service -am -Dtest='UserSettingsServiceImplTest,UserSettingsControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `setPrivateAccount` and for the 7-argument `UserSettingsResponse` constructor.

- [ ] **Step 4: Accept the key**

In `services/user-service/src/main/java/com/tiktok/userservice/dto/request/UpdateUserSettingsRequest.java`:

In the class javadoc, replace:

```java
 * <p>Clearing only applies to the three nullable fields. An explicit null on an audience or on
 * {@code weeklyScreenTimeDigest} is a {@code VALIDATION_ERROR} rather than a no-op: those columns
 * are NOT NULL, and silently treating the key as absent is how "I set it and it didn't save"
 * happens.
```

with:

```java
 * <p>Clearing only applies to the three nullable fields. An explicit null on an audience, on
 * {@code weeklyScreenTimeDigest} or on {@code privateAccount} is a {@code VALIDATION_ERROR} rather
 * than a no-op: those columns are NOT NULL, and silently treating the key as absent is how "I set
 * it and it didn't save" happens.
```

Add after the `weeklyScreenTimeDigestSet` field:

```java
    /** Stored on the profile, not with the rest — see UserSettingsServiceImpl.changePrivacy. */
    private Boolean privateAccount;
    private boolean privateAccountSet;
```

Add after `setWeeklyScreenTimeDigest`:

```java
    public void setPrivateAccount(Boolean privateAccount) {
        this.privateAccount = privateAccount;
        this.privateAccountSet = true;
    }
```

- [ ] **Step 5: Answer it**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/dto/response/UserSettingsResponse.java`:

```java
package com.tiktok.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.entity.SettingsAudience;

import java.time.LocalTime;

/**
 * Every setting the owner can change, as a flat keyed object. Deliberately not the section/group
 * tree the settings page draws: layout is presentation and belongs to the client, and a server
 * that ships the layout needs a deploy to move a row.
 *
 * <p>An account with no stored row gets this filled from {@link
 * com.tiktok.userservice.entity.UserSettings#defaultsFor}, so a caller never has to handle "no
 * settings yet".
 */
public record UserSettingsResponse(
        SettingsAudience commentAudience,
        SettingsAudience dmAudience,
        /** Null is off, not "unset" — see V10. */
        Integer screenTimeLimitMinutes,
        @JsonFormat(pattern = UpdateUserSettingsRequest.TIME_FORMAT) LocalTime sleepHoursStart,
        @JsonFormat(pattern = UpdateUserSettingsRequest.TIME_FORMAT) LocalTime sleepHoursEnd,
        boolean weeklyScreenTimeDigest,
        /**
         * Read off the profile rather than the settings row, because every viewer needs it there —
         * see V11. False until the profile exists.
         */
        boolean privateAccount
) {
}
```

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/mapper/UserSettingsMapper.java`:

```java
package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.UserSettings;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserSettingsMapper {

    /** {@code privateAccount} comes off the profile, not the settings row. */
    @Mapping(target = "privateAccount", source = "privateAccount")
    UserSettingsResponse toResponse(UserSettings settings, boolean privateAccount);

    /** The two fields other services may see — see {@link UserSettingsPolicyResponse}. */
    UserSettingsPolicyResponse toPolicy(UserSettings settings);
}
```

- [ ] **Step 6: Switch the flag in the settings transaction**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/service/UserSettingsServiceImpl.java`:

```java
package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.entity.UserSettings;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.mapper.UserSettingsMapper;
import com.tiktok.userservice.repository.UserProfileRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserSettingsServiceImpl implements UserSettingsService {

    private final UserSettingsRepository userSettingsRepository;
    private final UserSettingsMapper userSettingsMapper;
    private final UserProfileRepository userProfileRepository;
    private final UserEventPublisher userEventPublisher;
    private final FollowService followService;

    @Override
    @Transactional(readOnly = true)
    public UserSettingsResponse getOwnSettings(Long userId) {
        return userSettingsMapper.toResponse(currentOrDefaults(userId), isPrivate(userId));
    }

    @Override
    @Transactional
    public UserSettingsResponse updateOwnSettings(Long userId, UpdateUserSettingsRequest request) {
        UserSettings settings = currentOrDefaults(userId);

        SettingsAudience commentAudience = request.isCommentAudienceSet()
                ? audience("commentAudience", request.getCommentAudience())
                : settings.getCommentAudience();
        SettingsAudience dmAudience = request.isDmAudienceSet()
                ? audience("dmAudience", request.getDmAudience())
                : settings.getDmAudience();
        boolean weeklyDigest = request.isWeeklyScreenTimeDigestSet()
                ? required("weeklyScreenTimeDigest", request.getWeeklyScreenTimeDigest())
                : settings.isWeeklyScreenTimeDigest();
        // Null here means the key was absent: leave the flag where it is.
        Boolean privateAccount = request.isPrivateAccountSet()
                ? required("privateAccount", request.getPrivateAccount())
                : null;

        Integer screenTimeLimitMinutes = request.isScreenTimeLimitMinutesSet()
                ? request.getScreenTimeLimitMinutes()
                : settings.getScreenTimeLimitMinutes();
        LocalTime sleepHoursStart = request.isSleepHoursStartSet()
                ? request.getSleepHoursStart()
                : settings.getSleepHoursStart();
        LocalTime sleepHoursEnd = request.isSleepHoursEndSet()
                ? request.getSleepHoursEnd()
                : settings.getSleepHoursEnd();

        requireValidSleepWindow(sleepHoursStart, sleepHoursEnd);

        settings.replaceWith(commentAudience, dmAudience, screenTimeLimitMinutes,
                sleepHoursStart, sleepHoursEnd, weeklyDigest);

        // Insert on the first write, update on the rest: currentOrDefaults hands back either a
        // managed row or a transient one, and save covers both.
        //
        // Same check-then-act race as FollowServiceImpl.follow — two devices writing for the first
        // time both read no row and both insert, and only uq_user_settings_user_id stops the
        // second. saveAndFlush forces that to surface here rather than at commit, and it is
        // reported as the 409 two concurrent writes already produce on an existing row: the
        // caller's move is identical either way, re-read and try again, and the retry finds the
        // row and updates it.
        UserSettings saved;
        try {
            saved = userSettingsRepository.saveAndFlush(settings);
        } catch (DataIntegrityViolationException ex) {
            throw new OptimisticLockingFailureException(
                    "Settings for user " + userId + " were created by another request", ex);
        }

        // After the settings row, never before it: the flag's bulk update clears the persistence
        // context, which would detach the row mid-write. One transaction either way, so a failure
        // on either side leaves both where they were.
        boolean isPrivate = privateAccount == null ? isPrivate(userId) : changePrivacy(userId, privateAccount);
        return userSettingsMapper.toResponse(saved, isPrivate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSettingsPolicyResponse> getSettingsPolicies(List<Long> userIds) {
        // Against the raw list, before the distinct below, exactly like
        // UserProfileService.getByUserIds: what the cap guards is the query string being parsed and
        // bound, and duplicates pay for that in full.
        if (userIds.size() > UserProfileService.MAX_BATCH_IDS) {
            throw new TooManyProfileIdsException(UserProfileService.MAX_BATCH_IDS);
        }

        // Nulls dropped like getStatsByUserIds does: "ids=1,,2" binds one, and an answer carrying
        // "userId": null would be a row no caller can key by.
        Set<Long> distinctIds = userIds.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinctIds.isEmpty()) {
            return List.of();
        }

        Map<Long, UserSettings> stored = userSettingsRepository.findByUserIdInAndDeletedAtIsNull(distinctIds)
                .stream()
                .collect(Collectors.toMap(UserSettings::getUserId, Function.identity()));

        // Every id asked for is answered, with the defaults where there is no row — see the
        // interface for why absence is not an option here.
        return distinctIds.stream()
                .map(id -> stored.getOrDefault(id, UserSettings.defaultsFor(id)))
                .map(userSettingsMapper::toPolicy)
                .toList();
    }

    /**
     * Flips the flag only where it differs, in the database rather than after a read, so two
     * PATCHes racing to the same value announce one change between them.
     *
     * @return the value now stored
     */
    private boolean changePrivacy(Long userId, boolean privateAccount) {
        if (userProfileRepository.updatePrivateAccount(userId, privateAccount) == 0) {
            // Nothing matched: either the flag already had this value — a no-op that announces
            // nothing — or there is no profile to put it on.
            if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(userId)) {
                throw new UserProfileNotFoundException(userId);
            }
            return privateAccount;
        }

        userEventPublisher.publishPrivacyChanged(userId, privateAccount);
        if (!privateAccount) {
            // Nothing gates following any more, and these people already asked.
            followService.approveAllFollowRequests(userId);
        }
        return privateAccount;
    }

    /** Off the profile, where every viewer reads it; false until the profile row exists. */
    private boolean isPrivate(Long userId) {
        return userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .map(UserProfile::isPrivateAccount)
                .orElse(false);
    }

    /**
     * The stored row, or a transient one carrying the defaults. The transient one is never saved
     * on the read path — {@link #getOwnSettings} only maps it — so reading somebody's settings
     * does not create a row for them.
     */
    private UserSettings currentOrDefaults(Long userId) {
        return userSettingsRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseGet(() -> UserSettings.defaultsFor(userId));
    }

    private static SettingsAudience audience(String field, String value) {
        // Already matched against the three names by @Pattern; null is what is left, and it means
        // the client sent the key explicitly set to null on a column that has no null.
        return SettingsAudience.valueOf(required(field, value));
    }

    private static <T> T required(String field, T value) {
        if (value == null) {
            throw new InvalidUserSettingsException(field + ": cannot be null");
        }
        return value;
    }

    /**
     * Both ends or neither, and not the same minute twice. A start later than the end is left
     * alone on purpose: a window that crosses midnight is the normal case for sleep hours.
     */
    private static void requireValidSleepWindow(LocalTime start, LocalTime end) {
        if ((start == null) != (end == null)) {
            throw new InvalidUserSettingsException(
                    "sleepHoursStart and sleepHoursEnd must be set together or cleared together");
        }
        if (start != null && start.equals(end)) {
            throw new InvalidUserSettingsException("sleepHoursStart and sleepHoursEnd must differ");
        }
    }
}
```

- [ ] **Step 7: Say it in the contract**

In `services/user-service/src/main/java/com/tiktok/userservice/service/UserSettingsService.java`, replace the javadoc of `updateOwnSettings`:

```java
    /**
     * Applies a patch and answers the whole object as it now stands.
     *
     * <p>Inserts the row if this is the account's first write, carrying the patch over the
     * defaults. Two devices writing at once produce {@code CONCURRENT_MODIFICATION} through
     * {@code BaseEntity}'s version column.
     */
```

with:

```java
    /**
     * Applies a patch and answers the whole object as it now stands.
     *
     * <p>Inserts the row if this is the account's first write, carrying the patch over the
     * defaults. Two devices writing at once produce {@code CONCURRENT_MODIFICATION} through
     * {@code BaseEntity}'s version column.
     *
     * <p>{@code privateAccount} is kept on the profile, not in this row. Changing it announces
     * {@code UserPrivacyChangedEvent}, and switching back to public approves every pending follow
     * request — all in this one transaction. Sent before the profile exists, it is
     * {@code USER_PROFILE_NOT_FOUND}.
     */
```

In `services/user-service/src/main/java/com/tiktok/userservice/controller/UserSettingsController.java`, replace:

```java
            description = "True partial update: an absent key is left alone, and an explicit null "
                    + "clears one of the three nullable fields. Unlike PATCH /me there is no "
                    + "empty-string-clears rule. 400 VALIDATION_ERROR, 409 CONCURRENT_MODIFICATION.")
```

with:

```java
            description = "True partial update: an absent key is left alone, and an explicit null "
                    + "clears one of the three nullable fields. Unlike PATCH /me there is no "
                    + "empty-string-clears rule. Switching privateAccount off approves every "
                    + "pending follow request. 400 VALIDATION_ERROR, 404 USER_PROFILE_NOT_FOUND "
                    + "when privateAccount is sent before the profile exists, "
                    + "409 CONCURRENT_MODIFICATION.")
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -pl services/user-service -am -Dtest='UserSettingsServiceImplTest,UserSettingsControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 9: Run user-service's full suite**

Run: `./mvnw test -pl services/user-service -am`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add services/user-service/src/main/java/com/tiktok/userservice/dto/request/UpdateUserSettingsRequest.java \
        services/user-service/src/main/java/com/tiktok/userservice/dto/response/UserSettingsResponse.java \
        services/user-service/src/main/java/com/tiktok/userservice/mapper/UserSettingsMapper.java \
        services/user-service/src/main/java/com/tiktok/userservice/service/UserSettingsService.java \
        services/user-service/src/main/java/com/tiktok/userservice/service/UserSettingsServiceImpl.java \
        services/user-service/src/main/java/com/tiktok/userservice/controller/UserSettingsController.java \
        services/user-service/src/test/java/com/tiktok/userservice/service/UserSettingsServiceImplTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/controller/UserSettingsControllerTest.java
git commit -m "$(cat <<'EOF'
feat(user): private account switch on the settings API

GET /me/settings answers privateAccount off the profile, and PATCH accepts it
three-valued like the other keys (explicit null is VALIDATION_ERROR). A real
change queues UserPrivacyChangedEvent in the same transaction; switching back
to public also approves every pending follow request. An unchanged value
queues nothing.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Relationship, batch follow check and private lists

**Files:**
- Create: `services/user-service/src/main/java/com/tiktok/userservice/exception/PrivateAccountException.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/dto/response/FriendshipResponse.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/repository/UserFollowRepository.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/service/FollowService.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/service/FollowServiceImpl.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/controller/InternalFollowController.java`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/controller/FollowController.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/FollowServiceImplTest.java`
- Test: `services/user-service/src/test/java/com/tiktok/userservice/controller/UserSettingsControllerTest.java`

**Interfaces:**
- Consumes:
  - Task 2: `UserProfile.isPrivateAccount()`.
  - Task 3: `FollowRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(Long, Long)`, plus `hasEdge` in `FollowServiceImpl`.
- Produces:
  - `record FriendshipResponse(Long userId, boolean friends, boolean following, boolean requested, boolean privateAccount)`.
  - `FollowService.friendship(Long viewerId, Long otherUserId)`. `viewerId` may be null, meaning an anonymous viewer.
  - `List<Long> FollowService.followingAmong(Long followerId, List<Long> userIds)`.
  - Tokenless endpoints:
    - `GET /api/v1/users/internal/following?followerId=&ids=` answers `ApiResponse<List<Long>>`, the subset followed. More than 100 ids is `TOO_MANY_PROFILE_IDS`.
    - `GET /api/v1/users/internal/friendship?userB=[&userA=]`. Without `userA`, only `privateAccount` can be true.
  - `PrivateAccountException()`: 403 `PRIVATE_ACCOUNT` from `listFollowers` and `listFollowing`.

- [ ] **Step 1: Write the failing service tests**

In `services/user-service/src/test/java/com/tiktok/userservice/service/FollowServiceImplTest.java`:

Add these imports:

```java
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.exception.PrivateAccountException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;

import java.util.List;
import java.util.stream.LongStream;
```

Append inside the class:

```java
    @Test
    @Transactional
    void friendship_saysFollowingRequestedAndPrivate() {
        makePrivate(2L);
        followService.follow(1L, 2L);

        FriendshipResponse requested = followService.friendship(1L, 2L);
        assertThat(requested.following()).isFalse();
        assertThat(requested.requested()).isTrue();
        assertThat(requested.privateAccount()).isTrue();

        followService.approveFollowRequest(2L, 1L);

        FriendshipResponse following = followService.friendship(1L, 2L);
        assertThat(following.following()).isTrue();
        assertThat(following.requested()).isFalse();
        assertThat(following.friends()).isFalse();
    }

    /** chat-service and interaction-service ask for anonymous viewers too; only the flag can be true. */
    @Test
    @Transactional
    void friendship_forAnAnonymousViewer_onlySaysWhetherTheAccountIsPrivate() {
        makePrivate(2L);

        FriendshipResponse anonymous = followService.friendship(null, 2L);

        assertThat(anonymous.privateAccount()).isTrue();
        assertThat(anonymous.following()).isFalse();
        assertThat(anonymous.requested()).isFalse();
        assertThat(anonymous.friends()).isFalse();
    }

    @Test
    @Transactional
    void followingAmong_answersTheSubsetFollowed() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        followService.follow(1L, 2L);

        assertThat(followService.followingAmong(1L, List.of(2L, 3L, 999L))).containsExactly(2L);
    }

    @Test
    void followingAmong_overTheBatchCap_isRejected() {
        List<Long> ids = LongStream.rangeClosed(1, UserProfileService.MAX_BATCH_IDS + 1).boxed().toList();

        assertThatThrownBy(() -> followService.followingAmong(1L, ids))
                .isInstanceOf(TooManyProfileIdsException.class);
    }

    /** A private account's lists are for its followers: 403 to a stranger; the owner and followers pass. */
    @Test
    @Transactional
    void lists_ofAPrivateAccount_refuseAStrangerButNotAFollowerOrTheOwner() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        followService.follow(1L, 2L);
        makePrivate(2L);

        assertThat(followService.listFollowers(1L, 2L, PageRequest.of(0, 10)).getContent())
                .extracting(UserProfileResponse::userId).containsExactly(1L);
        assertThat(followService.listFollowing(2L, 2L, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThatThrownBy(() -> followService.listFollowers(3L, 2L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
        assertThatThrownBy(() -> followService.listFollowing(3L, 2L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
    }
```

- [ ] **Step 2: Write the failing controller tests**

In `services/user-service/src/test/java/com/tiktok/userservice/controller/UserSettingsControllerTest.java`, change:

```java
        when(followService.friendship(1L, 2L)).thenReturn(new FriendshipResponse(2L, true));
```

to:

```java
        when(followService.friendship(1L, 2L)).thenReturn(new FriendshipResponse(2L, true, true, false, false));
```

Append inside the class:

```java
    /** interaction-service asks for anonymous callers of a public tab, so the viewer is optional. */
    @Test
    void internalFriendship_withoutAViewer_isTheAnonymousQuestion() throws Exception {
        when(followService.friendship(null, 2L)).thenReturn(new FriendshipResponse(2L, false, false, false, true));

        mockMvc.perform(get("/api/v1/users/internal/friendship").param("userB", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.privateAccount").value(true));
    }

    /**
     * Same shadowing risk as the friendship check, and a worse one: {@code /users/{userId}/following}
     * is the following list, which needs a JWT. The literal segment must keep winning.
     */
    @Test
    void internalFollowing_answersWithoutAToken_andIsNotShadowedByTheFollowingList() throws Exception {
        when(followService.followingAmong(1L, List.of(7L, 8L))).thenReturn(List.of(8L));

        mockMvc.perform(get("/api/v1/users/internal/following")
                        .param("followerId", "1")
                        .param("ids", "7", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(8));
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -pl services/user-service -am -Dtest='FollowServiceImplTest,UserSettingsControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `following()`, `followingAmong` and `PrivateAccountException`.

- [ ] **Step 4: Widen the relationship answer**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/dto/response/FriendshipResponse.java`:

```java
package com.tiktok.userservice.dto.response;

/**
 * The viewer's relationship to {@code userId} — everything a Follow button needs in one answer.
 * {@code friends} is mutual following, what video-service's FRIENDS visibility means; false for
 * self and for an anonymous viewer, as are {@code following} and {@code requested}.
 */
public record FriendshipResponse(
        Long userId,
        boolean friends,
        /** The viewer follows {@code userId}. */
        boolean following,
        /** The viewer has a pending follow request to {@code userId} — the button reads "Requested". */
        boolean requested,
        /** {@code userId}'s account is private: the one field an anonymous viewer can see true. */
        boolean privateAccount
) {
}
```

Create `services/user-service/src/main/java/com/tiktok/userservice/exception/PrivateAccountException.java`:

```java
package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * A private account's followers/following list, asked for by someone who neither owns nor follows
 * it. A 403 where a block answers 404: that the account is private is already on its profile, so
 * the status confirms nothing.
 */
public class PrivateAccountException extends ForbiddenException {

    public PrivateAccountException() {
        super("PRIVATE_ACCOUNT", "This account is private");
    }
}
```

In `services/user-service/src/main/java/com/tiktok/userservice/repository/UserFollowRepository.java`:

Add the imports `import java.util.Collection;` and `import java.util.List;`.

Add after `findFollowingIdsVisibleTo`:

```java
    /**
     * The subset of {@code userIds} that {@code followerId} follows, in one query — the batch
     * follow check video-service asks once per page. No block filter: a block severs the edges.
     */
    @Query("select f.followingId from UserFollow f where f.followerId = :followerId "
            + "and f.followingId in :userIds and f.deletedAt is null")
    List<Long> findFollowingIdsAmong(@Param("followerId") Long followerId,
                                     @Param("userIds") Collection<Long> userIds);
```

In `services/user-service/src/main/java/com/tiktok/userservice/service/FollowService.java`:

Add the import `import java.util.List;`.

Replace:

```java
    /** Whether {@code viewerId} and {@code otherUserId} follow each other (mutual = "friends"). */
    FriendshipResponse friendship(Long viewerId, Long otherUserId);
```

with:

```java
    /** The viewer's relationship to {@code otherUserId}; {@code viewerId} is null for an anonymous viewer. */
    FriendshipResponse friendship(Long viewerId, Long otherUserId);

    /** The subset of {@code userIds} that {@code followerId} follows. At most 100 ids. */
    List<Long> followingAmong(Long followerId, List<Long> userIds);
```

- [ ] **Step 5: Answer it, and close a private account's lists**

In `services/user-service/src/main/java/com/tiktok/userservice/service/FollowServiceImpl.java`:

Add these imports:

```java
import com.tiktok.userservice.exception.PrivateAccountException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;

import java.util.Objects;
```

Replace the `friendship` method:

```java
    @Override
    @Transactional(readOnly = true)
    public FriendshipResponse friendship(Long viewerId, Long otherUserId) {
        // Read-only and edge-only: a non-existent user simply has no follow rows, so it answers
        // false rather than 404 — the caller (video-service's FRIENDS visibility check) only ever
        // needs the boolean, and self is never a friend.
        boolean friends = !viewerId.equals(otherUserId)
                && hasEdge(viewerId, otherUserId)
                && hasEdge(otherUserId, viewerId);
        return new FriendshipResponse(otherUserId, friends);
    }
```

with:

```java
    @Override
    @Transactional(readOnly = true)
    public FriendshipResponse friendship(Long viewerId, Long otherUserId) {
        // Read-only and edge-only: a non-existent user has no follow rows and no profile, so it
        // answers all false rather than 404, and self is never a friend.
        boolean privateAccount = isPrivate(otherUserId);
        if (viewerId == null || viewerId.equals(otherUserId)) {
            return new FriendshipResponse(otherUserId, false, false, false, privateAccount);
        }

        boolean following = hasEdge(viewerId, otherUserId);
        boolean friends = following && hasEdge(otherUserId, viewerId);
        boolean requested = !following && followRequestRepository
                .findByRequesterIdAndTargetIdAndDeletedAtIsNull(viewerId, otherUserId).isPresent();
        return new FriendshipResponse(otherUserId, friends, following, requested, privateAccount);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> followingAmong(Long followerId, List<Long> userIds) {
        // Against the raw list, before the distinct below, like UserProfileService.getByUserIds:
        // what the cap guards is the query string being parsed and bound.
        if (userIds.size() > UserProfileService.MAX_BATCH_IDS) {
            throw new TooManyProfileIdsException(UserProfileService.MAX_BATCH_IDS);
        }

        List<Long> distinctIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        return distinctIds.isEmpty()
                ? List.of()
                : userFollowRepository.findFollowingIdsAmong(followerId, distinctIds);
    }
```

In both `listFollowers` and `listFollowing`, replace the line:

```java
        profileVisibilityGuard.requireVisible(viewerId, userId);
```

with:

```java
        requireListVisible(viewerId, userId);
```

Add before `hasEdge`:

```java
    /**
     * The block guard first — still a 404 — then the private-account rule: a private account's
     * lists are for its followers.
     */
    private void requireListVisible(Long viewerId, Long userId) {
        profileVisibilityGuard.requireVisible(viewerId, userId);
        if (!viewerId.equals(userId) && isPrivate(userId) && !hasEdge(viewerId, userId)) {
            throw new PrivateAccountException();
        }
    }

    private boolean isPrivate(Long userId) {
        return userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .map(UserProfile::isPrivateAccount)
                .orElse(false);
    }
```

- [ ] **Step 6: Serve both service-to-service questions**

Replace the whole of `services/user-service/src/main/java/com/tiktok/userservice/controller/InternalFollowController.java`:

```java
package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The follow graph as service-to-service questions, for callers with no token to forward.
 *
 * <p>Separate from {@link FollowController} only because that class is mapped under
 * {@code /api/v1/users/{userId}}, which cannot also produce a literal {@code /internal/} path.
 * Being two mappings on the same URL shape is the point to watch: a request for
 * {@code /api/v1/users/internal/friendship} matches this one rather than
 * {@code /{userId}/friendship} because a literal segment outranks a path variable — and it has to,
 * since the other one requires a JWT and would answer 401 to the services that need this. The
 * same holds for {@code /internal/following} against {@code /{userId}/following}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Follows", description = "The follow graph and what a viewer may see of it")
public class InternalFollowController {

    private final FollowService followService;

    /**
     * Why this exists at all: {@code FRIENDS} on either settings audience needs a mutual-follow
     * check, and chat-service opens conversations from WebSocket frames that carry no
     * Authorization header. video-service's {@code FriendshipClient} copes by forwarding the
     * viewer's own token off the request context; chat has none to forward. interaction-service
     * asks it for a private account's Reposts tab, where the caller may be anonymous.
     */
    @Operation(summary = "Internal: the relationship between a viewer and an account",
            description = "Edge-only, like the authenticated version: an id with no account is "
                    + "all false, and an account is never its own friend. Without userA the viewer "
                    + "is anonymous, and only privateAccount can be true.")
    @GetMapping("/api/v1/users/internal/friendship")
    public ApiResponse<FriendshipResponse> friendship(@RequestParam(required = false) Long userA,
                                                      @RequestParam Long userB) {
        return ApiResponse.success(followService.friendship(userA, userB));
    }

    /**
     * video-service asks this once per request for every private owner on the page, rather than
     * once per owner.
     */
    @Operation(summary = "Internal: which of these accounts a user follows",
            description = "Answers the subset of ids that followerId follows. More than 100 ids is "
                    + "400 TOO_MANY_PROFILE_IDS.")
    @GetMapping("/api/v1/users/internal/following")
    public ApiResponse<List<Long>> followingAmong(@RequestParam Long followerId,
                                                  @RequestParam List<Long> ids) {
        return ApiResponse.success(followService.followingAmong(followerId, ids));
    }
}
```

In `services/user-service/src/main/java/com/tiktok/userservice/controller/FollowController.java`, replace:

```java
    @Operation(summary = "Whether the viewer and this user follow each other",
            description = "Edge-only: an id with no account is simply not a friend, not a 404.")
```

with:

```java
    @Operation(summary = "The viewer's relationship to this user",
            description = "following, requested (a pending follow request), friends (mutual) and "
                    + "whether the account is private — everything a Follow button needs. "
                    + "Edge-only: an id with no account is all false, not a 404.")
```

Replace:

```java
            description = "404 USER_PROFILE_NOT_FOUND if a block hides the account from the "
                    + "viewer. Accounts that blocked the viewer are absent from the page.")
```

with:

```java
            description = "404 USER_PROFILE_NOT_FOUND if a block hides the account from the "
                    + "viewer. 403 PRIVATE_ACCOUNT if the account is private and the viewer "
                    + "neither owns nor follows it. Accounts that blocked the viewer are absent "
                    + "from the page.")
```

`SecurityConfig` needs no change: `GET /api/v1/users/internal/**` is already `permitAll`, and api-gateway denies the prefix.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -pl services/user-service -am -Dtest='FollowServiceImplTest,UserSettingsControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Run user-service's full suite**

Run: `./mvnw test -pl services/user-service -am`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add services/user-service/src/main/java/com/tiktok/userservice/exception/PrivateAccountException.java \
        services/user-service/src/main/java/com/tiktok/userservice/dto/response/FriendshipResponse.java \
        services/user-service/src/main/java/com/tiktok/userservice/repository/UserFollowRepository.java \
        services/user-service/src/main/java/com/tiktok/userservice/service/FollowService.java \
        services/user-service/src/main/java/com/tiktok/userservice/service/FollowServiceImpl.java \
        services/user-service/src/main/java/com/tiktok/userservice/controller/InternalFollowController.java \
        services/user-service/src/main/java/com/tiktok/userservice/controller/FollowController.java \
        services/user-service/src/test/java/com/tiktok/userservice/service/FollowServiceImplTest.java \
        services/user-service/src/test/java/com/tiktok/userservice/controller/UserSettingsControllerTest.java
git commit -m "$(cat <<'EOF'
feat(user): relationship answer, batch follow check and private lists

FriendshipResponse now says following, requested and privateAccount, so one
call drives the Follow button; the internal endpoint takes an optional viewer.
GET /users/internal/following answers which of up to 100 accounts a user
follows, for video-service's one call per page. A private account's
followers/following lists answer 403 PRIVATE_ACCOUNT to anyone but its owner
and followers.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `ownerPrivate` on videos, and the audience on their events

**Files:**
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/entity/Video.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/dto/response/VideoResponse.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/service/QuarantinedMediaUrls.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepository.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/event/producer/VideoEventPublisher.java`
- Modify: `libs/event-schema/src/main/java/com/tiktok/event/video/VideoPublishedEvent.java` (comment only)
- Modify: `libs/event-schema/src/main/java/com/tiktok/event/video/VideoVisibilityChangedEvent.java` (comments only)
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/event/producer/VideoEventPublisherTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/repository/OutboxPollQueryTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/mapper/VideoMapperTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java`
- Mechanical (constructor arity only):
  - `services/video-service/src/test/java/com/tiktok/videoservice/controller/VideoControllerTest.java`
  - `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java`
  - `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `Video.isOwnerPrivate()`, with builder field `ownerPrivate`.
  - `VideoResponse` gains a last component, `boolean ownerPrivate`. `VideoMapper` maps it with no change.
  - Package-private `static String VideoEventPublisher.audience(Video video)`.
  - `VideoRepository.findTop100ByVisibilityEventPendingAtIsNotNullAndEventPublishedAtIsNotNullOrderByVisibilityEventPendingAtAsc()` replaces `findTop100ByVisibilityEventPendingAtIsNotNullOrderByVisibilityEventPendingAtAsc()`.

- [ ] **Step 1: Write the failing tests**

Append to `services/video-service/src/test/java/com/tiktok/videoservice/event/producer/VideoEventPublisherTest.java`, inside the class:

```java
    /**
     * A private owner's PUBLIC video goes out as FOLLOWERS: search, For You and trending keep their
     * own copy and show only PUBLIC, so the one word keeps it off all three.
     */
    @Test
    void publishPending_aPrivateOwnersPublicVideo_goesOutAsFollowers() {
        Video video = videoWith(VideoVisibility.PUBLIC, true);
        when(videoRepository.findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(video));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        assertThat(visibilityOf(captureRecord())).isEqualTo("FOLLOWERS");
    }

    @Test
    void publishPendingVisibilityChanges_announcesTheAudience() {
        Video video = videoWith(VideoVisibility.PUBLIC, true);
        video.changeVisibility(VideoVisibility.PUBLIC);
        when(videoRepository
                .findTop100ByVisibilityEventPendingAtIsNotNullAndEventPublishedAtIsNotNullOrderByVisibilityEventPendingAtAsc())
                .thenReturn(List.of(video));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPendingVisibilityChanges();

        ProducerRecord<String, String> record = captureRecord();
        assertThat(eventTypeOf(record)).isEqualTo("VideoVisibilityChangedEvent");
        assertThat(visibilityOf(record)).isEqualTo("FOLLOWERS");
    }

    /** The stored visibility is the owner's choice and stays; only the announced word changes. */
    @Test
    void audience_isTheVisibilityExceptForAPrivateOwnersPublicVideo() {
        assertThat(VideoEventPublisher.audience(videoWith(VideoVisibility.PUBLIC, false))).isEqualTo("PUBLIC");
        assertThat(VideoEventPublisher.audience(videoWith(VideoVisibility.PUBLIC, true))).isEqualTo("FOLLOWERS");
        assertThat(VideoEventPublisher.audience(videoWith(VideoVisibility.FRIENDS, true))).isEqualTo("FRIENDS");
        assertThat(VideoEventPublisher.audience(videoWith(VideoVisibility.PRIVATE, true))).isEqualTo("PRIVATE");
        // No stored visibility reads as PUBLIC, so it takes the same turn.
        assertThat(VideoEventPublisher.audience(videoWith(null, true))).isEqualTo("FOLLOWERS");
    }

    private Video videoWith(VideoVisibility visibility, boolean ownerPrivate) {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("My video")
                .rawFileUrl("s3://video-media/raw/1.mp4")
                .visibility(visibility)
                .ownerPrivate(ownerPrivate)
                .tags(List.of("dance"))
                .status(VideoStatus.PUBLISHED)
                .build();
    }

    private String visibilityOf(ProducerRecord<String, String> record) {
        try {
            return objectMapper.readTree(record.value()).get("visibility").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
```

Append to `services/video-service/src/test/java/com/tiktok/videoservice/repository/OutboxPollQueryTest.java`, inside the class:

```java
    /**
     * The two polls run on separate threads. Without this, a visibility change queued before the
     * publication went out — routine once an owner's privacy switch stamps every video they have —
     * could reach the partition ahead of the video it is about.
     */
    @Test
    void visibilityPoll_waitsForThePublicationToBeAnnounced() {
        Video unannounced = save(false);
        unannounced.changeVisibility(VideoVisibility.PRIVATE);
        videoRepository.save(unannounced);
        Video announced = save(false);
        announced.markEventPublished();
        announced.changeVisibility(VideoVisibility.PRIVATE);
        videoRepository.save(announced);

        assertThat(videoRepository
                .findTop100ByVisibilityEventPendingAtIsNotNullAndEventPublishedAtIsNotNullOrderByVisibilityEventPendingAtAsc())
                .extracting(Video::getId)
                .containsExactly(announced.getId());
    }
```

Append to `services/video-service/src/test/java/com/tiktok/videoservice/mapper/VideoMapperTest.java`, inside the class:

```java
    @Test
    void toResponse_carriesTheOwnersPrivacy() {
        Video video = Video.builder()
                .id("2")
                .userId(7L)
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .ownerPrivate(true)
                .build();

        assertThat(mapper.toResponse(video).ownerPrivate()).isTrue();
    }
```

In `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java`, add the import `import com.fasterxml.jackson.databind.node.ObjectNode;`, then append inside the class:

```java
    /**
     * Entries written before ownerPrivate existed stay in Redis for up to a TTL after the deploy.
     * They must read as a public owner's video — which is what they were — not fail to parse.
     */
    @Test
    void get_anEntryCachedBeforeOwnerPrivateExisted_readsAsAPublicOwners() throws Exception {
        ObjectNode old = objectMapper.valueToTree(video("A"));
        old.remove("ownerPrivate");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("video:A")).thenReturn(objectMapper.writeValueAsString(old));

        assertThat(cache().get("A")).hasValueSatisfying(video -> assertThat(video.ownerPrivate()).isFalse());
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -pl services/video-service -am -Dtest='VideoEventPublisherTest,OutboxPollQueryTest,VideoMapperTest,VideoCacheTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `ownerPrivate`, `audience` and the renamed poll query.

- [ ] **Step 3: Put the flag on the document**

In `services/video-service/src/main/java/com/tiktok/videoservice/entity/Video.java`, add after the `commentsDisabled` field:

```java
    /**
     * The owner's account is private, so a PUBLIC video is for their followers only. Copied from
     * owner_privacy when the video is published, and rewritten by UserPrivacyEventConsumer when the
     * owner switches: the public feed query filters on it and cannot ask user-service per row.
     * Absent on documents written before this field existed, which Mongo maps to {@code false} —
     * right for all of them, since no account could be private then.
     */
    private boolean ownerPrivate;
```

- [ ] **Step 4: Carry it on the response**

In `services/video-service/src/main/java/com/tiktok/videoservice/dto/response/VideoResponse.java`, replace:

```java
        boolean accountCommentsOff
) {

    /** This response for a video whose owner accepts comments from no one. */
    public VideoResponse withAccountCommentsOff() {
        return new VideoResponse(id, userId, title, description, thumbnailUrl, previewUrl, hlsUrl,
                durationSeconds, width, height, status, visibility, viewCount, likeCount, null,
                commentsDisabled, tags, createdAt, updatedAt, publishedAt, rawFileUrl, failureReason,
                takedownReason, deletedAt, purgeEventPublishedAt, moderation, true);
    }
}
```

with:

```java
        boolean accountCommentsOff,
        /**
         * The owner's account is private: a PUBLIC video is then for their followers only.
         * {@code visibility} still says PUBLIC, because that is what the owner chose for the video.
         * Clients can show a lock. Entries cached before this existed read as {@code false}.
         */
        boolean ownerPrivate
) {

    /** This response for a video whose owner accepts comments from no one. */
    public VideoResponse withAccountCommentsOff() {
        return new VideoResponse(id, userId, title, description, thumbnailUrl, previewUrl, hlsUrl,
                durationSeconds, width, height, status, visibility, viewCount, likeCount, null,
                commentsDisabled, tags, createdAt, updatedAt, publishedAt, rawFileUrl, failureReason,
                takedownReason, deletedAt, purgeEventPublishedAt, moderation, true, ownerPrivate);
    }
}
```

In `services/video-service/src/main/java/com/tiktok/videoservice/service/QuarantinedMediaUrls.java`, replace:

```java
                video.purgeEventPublishedAt(), video.moderation(), video.accountCommentsOff());
```

with:

```java
                video.purgeEventPublishedAt(), video.moderation(), video.accountCommentsOff(),
                video.ownerPrivate());
```

- [ ] **Step 5: Give every test constructor of `VideoResponse` its new argument**

The regex matches a constructor call whose arguments hold at most one level of parentheses, which is every site here (7 of them).

Run:
```bash
perl -0pi -e 's/new VideoResponse\(((?:[^()]++|\([^()]*\))*)\)/new VideoResponse($1, false)/g' \
  services/video-service/src/test/java/com/tiktok/videoservice/controller/VideoControllerTest.java \
  services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java \
  services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java
grep -rn -A3 "new VideoResponse(" services/video-service/src/test | grep -c "false, false)"
```
Expected: `7`.

- [ ] **Step 6: Announce the audience, and only after the publication**

In `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepository.java`, replace:

```java
    /**
     * Visibility-change outbox poll. Soft-deleted videos are included on purpose: a video made
     * private and then deleted still has to have the visibility change announced, because the
     * deletion event only reaches consumers that keep the video at all — and the change may be
     * the one that removes it from a read path the deletion would otherwise race.
     */
    List<Video> findTop100ByVisibilityEventPendingAtIsNotNullOrderByVisibilityEventPendingAtAsc();
```

with:

```java
    /**
     * Visibility-change outbox poll. Soft-deleted videos are included on purpose: a video made
     * private and then deleted still has to have the visibility change announced, because the
     * deletion event only reaches consumers that keep the video at all — and the change may be
     * the one that removes it from a read path the deletion would otherwise race.
     *
     * <p>Only videos whose publication the broker has acknowledged. The two polls run on separate
     * threads, and a change queued before the publication went out — routine once an owner's
     * privacy switch stamps every video they have — could otherwise reach the partition ahead of
     * the video it is about. The publication, built from the document when it is sent, already
     * carries the current audience.
     */
    List<Video> findTop100ByVisibilityEventPendingAtIsNotNullAndEventPublishedAtIsNotNullOrderByVisibilityEventPendingAtAsc();
```

In `services/video-service/src/main/java/com/tiktok/videoservice/event/producer/VideoEventPublisher.java`:

Add the import `import com.tiktok.videoservice.entity.VideoVisibility;`.

In `publishPendingVisibilityChanges`, replace:

```java
                .findTop100ByVisibilityEventPendingAtIsNotNullOrderByVisibilityEventPendingAtAsc();
```

with:

```java
                .findTop100ByVisibilityEventPendingAtIsNotNullAndEventPublishedAtIsNotNullOrderByVisibilityEventPendingAtAsc();
```

In `toPublishedRecord`, replace `video.getRawFileUrl(), visibilityName(video), video.getTags());` with:

```java
                video.getRawFileUrl(), audience(video), video.getTags());
```

In `toVisibilityRecord`, replace `video.getId(), video.getUserId(), visibilityName(video),` with:

```java
                video.getId(), video.getUserId(), audience(video),
```

Replace the whole `visibilityName` method, with its javadoc:

```java
    /**
     * Documents written before the visibility field existed have none. PUBLIC is what they
     * behaved as, so that is what the consumers are told rather than a null they would each have
     * to decide about.
     */
    private static String visibilityName(Video video) {
        return video.getVisibility() == null ? "PUBLIC" : video.getVisibility().name();
    }
```

with:

```java
    /**
     * Who may see the video, in the one word every consumer that keeps a copy understands: its
     * visibility, except that a private owner's PUBLIC video is FOLLOWERS. Consumers treat
     * anything but PUBLIC as hidden, so FOLLOWERS takes it out of search, For You and trending
     * with no code on their side.
     *
     * <p>Documents written before the visibility field existed have none. PUBLIC is what they
     * behaved as, so that is what they are taken to be rather than a null each consumer would
     * have to decide about.
     */
    static String audience(Video video) {
        VideoVisibility visibility = video.getVisibility() == null ? VideoVisibility.PUBLIC : video.getVisibility();
        return visibility == VideoVisibility.PUBLIC && video.isOwnerPrivate() ? "FOLLOWERS" : visibility.name();
    }
```

- [ ] **Step 7: Say FOLLOWERS in the event contracts**

In `libs/event-schema/src/main/java/com/tiktok/event/video/VideoPublishedEvent.java`, replace the comment above `String visibility`:

```java
        // Who is allowed to see the video: PUBLIC, FRIENDS or PRIVATE, as video-service's
        // VideoVisibility names them. Carried because search-service builds its own read path off
        // this event and never calls video-service, so without it a PRIVATE video is returned to
        // any anonymous search — status alone says the video finished moderation, not who may see
        // it. Null only on events from a producer older than this field; consumers read that as
        // PUBLIC, which is what the producer of the day only ever published.
```

with:

```java
        // Who is allowed to see the video: PUBLIC, FRIENDS or PRIVATE, as video-service's
        // VideoVisibility names them — or FOLLOWERS, a PUBLIC video whose owner's account is
        // private. Anything but PUBLIC is hidden on every read path that keeps its own copy.
        // Carried because search-service builds its own read path off this event and never calls
        // video-service, so without it a PRIVATE video is returned to any anonymous search —
        // status alone says the video finished moderation, not who may see it. Null only on events
        // from a producer older than this field; consumers read that as PUBLIC, which is what the
        // producer of the day only ever published.
```

In `libs/event-schema/src/main/java/com/tiktok/event/video/VideoVisibilityChangedEvent.java`, replace:

```java
 * The owner moved a video between PUBLIC, FRIENDS and PRIVATE. Published on the same topic and
```

with:

```java
 * The owner moved a video between PUBLIC, FRIENDS and PRIVATE, or switched their account between
 * private and public — which moves a PUBLIC video to FOLLOWERS and back. Published on the same topic and
```

Replace:

```java
        /** PUBLIC, FRIENDS or PRIVATE, as video-service's VideoVisibility names them. */
```

with:

```java
        /** PUBLIC, FRIENDS, PRIVATE or FOLLOWERS — see {@link VideoPublishedEvent#visibility()}. */
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -pl services/video-service -am -Dtest='VideoEventPublisherTest,OutboxPollQueryTest,VideoMapperTest,VideoCacheTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 9: Run video-service's full suite**

Run: `./mvnw test -pl services/video-service -am`
Expected: BUILD SUCCESS.

- [ ] **Step 10: Commit**

```bash
git add services/video-service/src/main/java/com/tiktok/videoservice/entity/Video.java \
        services/video-service/src/main/java/com/tiktok/videoservice/dto/response/VideoResponse.java \
        services/video-service/src/main/java/com/tiktok/videoservice/service/QuarantinedMediaUrls.java \
        services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepository.java \
        services/video-service/src/main/java/com/tiktok/videoservice/event/producer/VideoEventPublisher.java \
        libs/event-schema/src/main/java/com/tiktok/event/video/VideoPublishedEvent.java \
        libs/event-schema/src/main/java/com/tiktok/event/video/VideoVisibilityChangedEvent.java \
        services/video-service/src/test/java/com/tiktok/videoservice/event/producer/VideoEventPublisherTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/repository/OutboxPollQueryTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/mapper/VideoMapperTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/controller/VideoControllerTest.java
git commit -m "$(cat <<'EOF'
feat(video): announce a private owner's public videos as FOLLOWERS

Video and VideoResponse carry ownerPrivate (absent reads false). Both video
events now carry the effective audience instead of the stored visibility, so
search, recommendation and chat drop those videos with no change on their
side. The visibility poll waits for the publication to be acknowledged, so a
change can no longer overtake its video on the partition.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Keep each owner's privacy and stamp it on their videos

**Files:**
- Create: `services/video-service/src/main/java/com/tiktok/videoservice/entity/OwnerPrivacy.java`
- Create: `services/video-service/src/main/java/com/tiktok/videoservice/repository/OwnerPrivacyRepository.java`
- Create: `services/video-service/src/main/java/com/tiktok/videoservice/event/consumer/UserPrivacyEventConsumer.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoCache.java`
- Modify: `docs/superpowers/specs/2026-09-26-private-account-design.md` (§5.1 step 1)
- Test (new): `services/video-service/src/test/java/com/tiktok/videoservice/event/consumer/UserPrivacyEventConsumerTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `UserPrivacyChangedEvent(eventId, occurredAt, userId, privateAccount)`.
  - Task 6: the `Video.ownerPrivate` field.
- Produces:
  - Document `OwnerPrivacy(Long userId, boolean privateAccount, Instant changedAt)`, with getters. It lives in collection `owner_privacy`, keyed `_id = userId`.
  - `OwnerPrivacyRepository` queries:
    - `boolean existsByUserIdAndPrivateAccountTrue(Long userId)`
    - `List<OwnerPrivacy> findByUserIdInAndPrivateAccountTrue(Collection<Long> userIds)`
  - `List<String> VideoRepositoryCustom.markOwnerPrivacy(Long userId, boolean privateAccount, Instant pendingAt)`. It returns the ids of the videos it stamped.
  - `VideoCache.evictAll(Collection<String> videoIds)`.

- [ ] **Step 1: Write the failing tests**

Create `services/video-service/src/test/java/com/tiktok/videoservice/event/consumer/UserPrivacyEventConsumerTest.java`:

```java
package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserPrivacyChangedEvent;
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.OwnerPrivacyRepository;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
import com.tiktok.videoservice.repository.VideoRepository;
import com.tiktok.videoservice.service.VideoCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserPrivacyEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    // Mocked because what matters here is which entries are evicted; VideoCacheTest covers Redis.
    @MockBean
    private VideoCache videoCache;

    @Autowired
    private UserPrivacyEventConsumer consumer;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private OwnerPrivacyRepository ownerPrivacyRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
        ownerPrivacyRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void goingPrivate_stampsEveryLiveVideo_queuesItsAnnouncement_andEvictsIt() throws Exception {
        Video first = save(1L, false);
        Video second = save(1L, false);
        Video deleted = save(1L, true);
        Video someoneElses = save(2L, false);

        consumer.onMessage(json(UserPrivacyChangedEvent.of(1L, true)));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isTrue();
        assertThat(videoRepository.findAllById(List.of(first.getId(), second.getId())))
                .hasSize(2)
                .allSatisfy(video -> {
                    assertThat(video.isOwnerPrivate()).isTrue();
                    // The per-video outbox re-announces it with the audience that now follows.
                    assertThat(video.getVisibilityEventPendingAt()).isNotNull();
                });
        assertThat(videoRepository.findById(deleted.getId()).orElseThrow().isOwnerPrivate()).isFalse();
        assertThat(videoRepository.findById(someoneElses.getId()).orElseThrow().isOwnerPrivate()).isFalse();

        ArgumentCaptor<Collection<String>> evicted = ArgumentCaptor.forClass(Collection.class);
        verify(videoCache).evictAll(evicted.capture());
        assertThat(evicted.getValue()).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    /** A replay from the DLT, older than what is recorded, must not undo the newer change. */
    @Test
    void anOlderEvent_arrivingAfterANewerOne_changesNothing() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent older = UserPrivacyChangedEvent.of(1L, true);
        UserPrivacyChangedEvent newer = new UserPrivacyChangedEvent(
                UUID.randomUUID().toString(), older.occurredAt().plusSeconds(1), 1L, false);

        consumer.onMessage(json(newer));
        consumer.onMessage(json(older));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isFalse();
        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isFalse();
        verify(videoCache, times(1)).evictAll(any());
    }

    /**
     * What a first attempt leaves behind when it fails between its two writes: the flag recorded,
     * the videos not stamped, the claim released for redelivery. The redelivery carries the same
     * occurredAt as what is recorded, so the guard has to let an equal one through.
     */
    @Test
    void aRedeliveryAfterAFailedFanOut_stillStampsTheVideos() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent event = UserPrivacyChangedEvent.of(1L, true);
        ownerPrivacyRepository.save(new OwnerPrivacy(1L, true, event.occurredAt()));

        consumer.onMessage(json(event));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isTrue();
    }

    @Test
    void goingPublicAgain_clearsTheFlag() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent goingPrivate = UserPrivacyChangedEvent.of(1L, true);
        consumer.onMessage(json(goingPrivate));

        consumer.onMessage(json(new UserPrivacyChangedEvent(
                UUID.randomUUID().toString(), goingPrivate.occurredAt().plusSeconds(1), 1L, false)));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isFalse();
        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isFalse();
    }

    private String json(UserPrivacyChangedEvent event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    private Video save(long userId, boolean deleted) {
        String id = Video.newId();
        Video video = Video.builder()
                .id(id)
                .userId(userId)
                .title("t")
                // Distinct per video: rawFileUrl is uniquely indexed.
                .rawFileUrl("s3://video-media/raw/%d/%s.mp4".formatted(userId, id))
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
        if (deleted) {
            video.markDeleted();
        }
        return videoRepository.save(video);
    }
}
```

In `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java`:

Add the import `import static org.mockito.Mockito.verify;`.

In `everyOperationFailsOpenWhenRedisIsDown`, add after the `when(redisTemplate.delete(anyString())).thenThrow(down);` line:

```java
        when(redisTemplate.delete(anyCollection())).thenThrow(down);
```

and add after `assertThatCode(() -> cache.evict("A")).doesNotThrowAnyException();`:

```java
        assertThatCode(() -> cache.evictAll(List.of("A"))).doesNotThrowAnyException();
```

In `whenDisabled_noRedisCommandIsIssued`, add after `cache.evict("A");`:

```java
        cache.evictAll(List.of("A"));
```

Append inside the class:

```java
    /** An owner switching their account touches every video they have: one DEL, not one per video. */
    @Test
    void evictAll_deletesEveryKeyInOneCommand() {
        cache().evictAll(List.of("A", "B"));

        verify(redisTemplate).delete(List.of("video:A", "video:B"));
    }

    @Test
    void evictAll_ofNothing_issuesNoCommand() {
        cache().evictAll(List.of());

        org.mockito.Mockito.verifyNoInteractions(redisTemplate);
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -pl services/video-service -am -Dtest='UserPrivacyEventConsumerTest,VideoCacheTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `UserPrivacyEventConsumer`, `OwnerPrivacy`, `OwnerPrivacyRepository` and `evictAll`.

- [ ] **Step 3: Add video-service's copy of who is private**

Create `services/video-service/src/main/java/com/tiktok/videoservice/entity/OwnerPrivacy.java`:

```java
package com.tiktok.videoservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * video-service's own answer to "is this owner private", for the paths that need it without a
 * video in hand: publishing, the profile grid, the Following feed. Written only by
 * UserPrivacyEventConsumer.
 *
 * <p>An owner with no document is public — right for every account that existed before this did,
 * since none of them could be private.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "owner_privacy")
public class OwnerPrivacy {

    @Id
    private Long userId;

    private boolean privateAccount;

    /** When user-service made the change: an older event arriving later must not undo a newer one. */
    private Instant changedAt;
}
```

Create `services/video-service/src/main/java/com/tiktok/videoservice/repository/OwnerPrivacyRepository.java`:

```java
package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.OwnerPrivacy;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface OwnerPrivacyRepository extends MongoRepository<OwnerPrivacy, Long> {

    boolean existsByUserIdAndPrivateAccountTrue(Long userId);

    /** The private owners among {@code userIds}, in one query on _id. */
    List<OwnerPrivacy> findByUserIdInAndPrivateAccountTrue(Collection<Long> userIds);
}
```

- [ ] **Step 4: Stamp an owner's videos in one write, and evict them in one command**

In `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java`, add after `clearVisibilityEventPending`:

```java
    /**
     * An owner's account switched between private and public: stamps the flag on every live video
     * they own and queues each one's visibility announcement, in one write.
     *
     * <p>Every video, not only the PUBLIC ones whose audience actually changes: consumers take a
     * repeated audience as the no-op it is, and one update keeps the flag and its announcement
     * together.
     *
     * @return the ids of those videos, for the caller to evict
     */
    List<String> markOwnerPrivacy(Long userId, boolean privateAccount, Instant pendingAt);
```

In `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java`, add after `clearVisibilityEventPending`:

```java
    @Override
    public List<String> markOwnerPrivacy(Long userId, boolean privateAccount, Instant pendingAt) {
        mongoTemplate.updateMulti(
                Query.query(where("userId").is(userId).and("deletedAt").is(null)),
                new Update()
                        .set("ownerPrivate", privateAccount)
                        .set("visibilityEventPendingAt", pendingAt)
                        .set("updatedAt", Instant.now()),
                Video.class);

        // Read after the write, so a video inserted in between is evicted too — harmless — rather
        // than missed.
        Query owned = Query.query(where("userId").is(userId).and("deletedAt").is(null));
        owned.fields().include("_id");
        return mongoTemplate.find(owned, Video.class).stream().map(Video::getId).toList();
    }
```

In `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoCache.java`:

Add the import `import java.util.Collection;`.

Add after `evict`:

```java
    /**
     * {@link #evict} for many ids in one DEL — an owner switching their account touches every video
     * they have. Same ceiling as evict: a reader already on its way back from Mongo can re-cache the
     * old entry for one TTL.
     */
    public void evictAll(Collection<String> videoIds) {
        if (!properties.enabled() || videoIds.isEmpty()) {
            return;
        }

        try {
            redisTemplate.delete(videoIds.stream().map(VideoCache::key).toList());
        } catch (Exception e) {
            log.debug("video cache evict failed for {} videos, entries left to expire", videoIds.size(), e);
        }
    }
```

- [ ] **Step 5: Consume the privacy events**

Create `services/video-service/src/main/java/com/tiktok/videoservice/event/consumer/UserPrivacyEventConsumer.java`:

```java
package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserPrivacyChangedEvent;
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.repository.VideoRepository;
import com.tiktok.videoservice.service.VideoCache;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Keeps video-service's copy of which owners are private, and stamps it on their videos. Three
 * steps, in this order, each safe to repeat:
 * <ol>
 *   <li>Record the flag in owner_privacy, unless a newer change is already there — and stop if so.</li>
 *   <li>Stamp every live video of the owner and queue its visibility announcement, which
 *       VideoEventPublisher sends with the audience that now follows.</li>
 *   <li>Evict those videos, so the visibility check stops reading the old flag off the cache.</li>
 * </ol>
 * Step 1 before step 2 is what VideoServiceImpl.publish relies on: it reads owner_privacy again
 * after its insert, so a video inserted in the middle of this is caught one way or the other.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPrivacyEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final MongoTemplate mongoTemplate;
    private final VideoRepository videoRepository;
    private final VideoCache videoCache;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.privacy-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserPrivacyChangedEvent event = objectMapper.readValue(payload, UserPrivacyChangedEvent.class);

        idempotentEventProcessor.runOnce(
                event.eventId(), event.getClass().getSimpleName(), () -> apply(event));
    }

    private void apply(UserPrivacyChangedEvent event) {
        if (!recordUnlessNewerIsThere(event)) {
            log.info("Privacy change of user {} from {} is older than the one recorded; ignoring it",
                    event.userId(), event.occurredAt());
            return;
        }

        List<String> videoIds = videoRepository.markOwnerPrivacy(
                event.userId(), event.privateAccount(), Instant.now());
        videoCache.evictAll(videoIds);
    }

    /**
     * The upsert matches only a document no newer than the event. A newer one does not match, so
     * the upsert tries to insert a second document with the same _id and the key refuses it — which
     * is how "a newer change is already recorded" reads here.
     *
     * <p>{@code lte}, not {@code lt}: a redelivery of this same event has to get through to the
     * stamping, because its first attempt may have failed after recording the flag.
     */
    private boolean recordUnlessNewerIsThere(UserPrivacyChangedEvent event) {
        try {
            mongoTemplate.upsert(
                    Query.query(where("_id").is(event.userId()).and("changedAt").lte(event.occurredAt())),
                    new Update()
                            .set("privateAccount", event.privateAccount())
                            .set("changedAt", event.occurredAt()),
                    OwnerPrivacy.class);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
```

- [ ] **Step 6: Amend the spec where the code had to differ**

In `docs/superpowers/specs/2026-09-26-private-account-design.md`, replace:

```markdown
1. Upsert `owner_privacy` only where `changedAt < event.occurredAt`. An older event replayed from
   the DLT after a newer one changes nothing, and stops here.
```

with:

```markdown
1. Upsert `owner_privacy` only where `changedAt <= event.occurredAt`. An older event replayed from
   the DLT after a newer one changes nothing, and stops here. `<=` rather than `<`: a redelivery of
   the same event, whose first attempt failed after this step, must still reach steps 2 and 3.
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -pl services/video-service -am -Dtest='UserPrivacyEventConsumerTest,VideoCacheTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Run video-service's full suite**

Run: `./mvnw test -pl services/video-service -am`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add services/video-service/src/main/java/com/tiktok/videoservice/entity/OwnerPrivacy.java \
        services/video-service/src/main/java/com/tiktok/videoservice/repository/OwnerPrivacyRepository.java \
        services/video-service/src/main/java/com/tiktok/videoservice/event/consumer/UserPrivacyEventConsumer.java \
        services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java \
        services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java \
        services/video-service/src/main/java/com/tiktok/videoservice/service/VideoCache.java \
        services/video-service/src/test/java/com/tiktok/videoservice/event/consumer/UserPrivacyEventConsumerTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoCacheTest.java \
        docs/superpowers/specs/2026-09-26-private-account-design.md
git commit -m "$(cat <<'EOF'
feat(video): keep each owner's privacy and stamp it on their videos

UserPrivacyEventConsumer records the flag in owner_privacy unless a newer
change is already there, then stamps ownerPrivate on every live video of the
owner, queues each one's visibility announcement and evicts them in one DEL.
The guard is <=, not <, so a redelivery after a failed fan-out still stamps
the videos; the spec is amended to match.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: A private owner's videos are for their followers

**Files:**
- Create: `services/video-service/src/main/java/com/tiktok/videoservice/client/FollowClient.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java`
- Test (new): `services/video-service/src/test/java/com/tiktok/videoservice/client/FollowClientTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java`

**Interfaces:**
- Consumes:
  - Task 5: `GET /api/v1/users/internal/following?followerId=&ids=` answers `ApiResponse<List<Long>>`, at most 100 ids per call.
  - Task 6: `VideoResponse.ownerPrivate()`.
- Produces:
  - `Set<Long> FollowClient.followedAmong(Long followerId, Collection<Long> ownerIds)`. It fails closed, and answers empty for a null follower.
  - The `VideoServiceImpl` constructor gains `FollowClient followClient` as its last parameter, after `userSettingsClient`.

- [ ] **Step 1: Write the failing tests**

Create `services/video-service/src/test/java/com/tiktok/videoservice/client/FollowClientTest.java`:

```java
package com.tiktok.videoservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FollowClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FollowClient client = new FollowClient(builder.build());

    @Test
    void followedAmong_asksOnceAndAnswersTheSubset() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/following?followerId=1&ids=7&ids=8"))
                .andRespond(withSuccess("""
                        {"success": true, "data": [8]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.followedAmong(1L, List.of(7L, 8L))).containsExactly(8L);
        server.verify();
    }

    /** Fails closed: an owner nobody could vouch for is one the viewer does not follow. */
    @Test
    void followedAmong_whenUserServiceFails_followsNobody() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/following?followerId=1&ids=7"))
                .andRespond(withServerError());

        assertThat(client.followedAmong(1L, List.of(7L))).isEmpty();
        server.verify();
    }

    @Test
    void followedAmong_forAnAnonymousViewer_asksNothing() {
        assertThat(client.followedAmong(null, List.of(7L))).isEmpty();
        server.verify();
    }

    /** user-service caps one batch at 100 ids, so a longer list goes in chunks rather than a 400. */
    @Test
    void followedAmong_overTheCap_asksInChunks() {
        List<Long> owners = LongStream.rangeClosed(1, 101).boxed().toList();
        server.expect(requestTo(startsWith("http://user-service/api/v1/users/internal/following?followerId=500&ids=1&")))
                .andRespond(withSuccess("""
                        {"success": true, "data": [1]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://user-service/api/v1/users/internal/following?followerId=500&ids=101"))
                .andRespond(withSuccess("""
                        {"success": true, "data": [101]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.followedAmong(500L, owners)).containsExactlyInAnyOrder(1L, 101L);
        server.verify();
    }
}
```

In `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java`:

Add the import `import static org.mockito.Mockito.verifyNoMoreInteractions;`.

Add after the `userSettingsClient` field:

```java
    private final com.tiktok.videoservice.client.FollowClient followClient =
            mock(com.tiktok.videoservice.client.FollowClient.class);
```

In the `new VideoServiceImpl(...)` call, replace:

```java
                blockClient,
                userSettingsClient);
```

with:

```java
                blockClient,
                userSettingsClient,
                followClient);
```

Append inside the class, before `publicVideo`:

```java
    /**
     * The §3 table for a private owner's PUBLIC video: its owner and followers see it; a stranger
     * and an anonymous viewer get the same 404 a PRIVATE video gets.
     */
    @Test
    void privateOwnersPublicVideo_isForTheOwnerAndFollowersOnly() {
        VideoResponse video = privateOwnersVideo("A", VideoVisibility.PUBLIC, OWNER);
        when(videoCache.get("A")).thenReturn(Optional.of(video));
        when(followClient.followedAmong(7L, Set.of(OWNER))).thenReturn(Set.of(OWNER));

        assertThat(videoService.getById(OWNER, "A").id()).isEqualTo("A");
        assertThat(videoService.getById(7L, "A").id()).isEqualTo("A");
        assertThatThrownBy(() -> videoService.getById(8L, "A")).isInstanceOf(VideoNotFoundException.class);
        assertThatThrownBy(() -> videoService.getById(null, "A")).isInstanceOf(VideoNotFoundException.class);
    }

    /** FRIENDS stays the friendship question: a mutual follow already implies following. */
    @Test
    void privateOwnersFriendsVideo_isStillForFriends() {
        VideoResponse video = privateOwnersVideo("A", VideoVisibility.FRIENDS, OWNER);
        when(videoCache.get("A")).thenReturn(Optional.of(video));
        when(friendshipClient.areFriends(OWNER, 7L)).thenReturn(true);

        assertThat(videoService.getById(7L, "A").id()).isEqualTo("A");
        assertThatThrownBy(() -> videoService.getById(8L, "A")).isInstanceOf(VideoNotFoundException.class);
        verifyNoInteractions(followClient);
    }

    /**
     * One follow call for the whole batch, naming only the private owners on it — never the
     * viewer's own account, and never a public owner.
     */
    @Test
    void getByIds_asksOnceForThePrivateOwnersOnThePage_andNeverAboutTheViewer() {
        VideoResponse followed = privateOwnersVideo("A", VideoVisibility.PUBLIC, 50L);
        VideoResponse notFollowed = privateOwnersVideo("B", VideoVisibility.PUBLIC, 51L);
        VideoResponse own = privateOwnersVideo("C", VideoVisibility.PUBLIC, 7L);
        VideoResponse publicOwners = publicVideo("D");
        when(videoCache.getAll(List.of("A", "B", "C", "D"))).thenReturn(Map.of(
                "A", followed, "B", notFollowed, "C", own, "D", publicOwners));
        when(followClient.followedAmong(7L, Set.of(50L, 51L))).thenReturn(Set.of(50L));

        assertThat(videoService.getByIds(7L, List.of("A", "B", "C", "D")))
                .containsExactly(followed, own, publicOwners);
        verify(followClient).followedAmong(7L, Set.of(50L, 51L));
        verifyNoMoreInteractions(followClient);
    }

    /** Public owners never trigger a call, so a normal feed page makes none. */
    @Test
    void getByIds_withNoPrivateOwnerOnThePage_makesNoFollowCall() {
        when(videoCache.getAll(List.of("A", "B")))
                .thenReturn(Map.of("A", publicVideo("A"), "B", publicVideo("B")));

        assertThat(videoService.getByIds(7L, List.of("A", "B"))).hasSize(2);
        verifyNoInteractions(followClient);
    }

    private static VideoResponse privateOwnersVideo(String id, VideoVisibility visibility, Long owner) {
        return new VideoResponse(
                id, owner, "title", "description", "https://cdn/t.jpg", "https://cdn/p.webp", "https://cdn/v.m3u8",
                10, 1080, 1920, VideoStatus.PUBLISHED, visibility, 0, 0, 0L, false, List.of(), Instant.now(),
                null, null, null, null, null, null, null, null, false, true);
    }
```

In `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java`, replace:

```java
                mock(com.tiktok.videoservice.client.UserSettingsClient.class));
```

with:

```java
                mock(com.tiktok.videoservice.client.UserSettingsClient.class),
                mock(com.tiktok.videoservice.client.FollowClient.class));
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -pl services/video-service -am -Dtest='FollowClientTest,VideoServiceImplCacheTest,VideoServiceImplUploadUrlFailureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol: class FollowClient`.

- [ ] **Step 3: Ask user-service once per request**

Create `services/video-service/src/main/java/com/tiktok/videoservice/client/FollowClient.java`:

```java
package com.tiktok.videoservice.client;

import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which of a request's private owners the viewer follows — what a private account's PUBLIC videos
 * need and only user-service knows. One call for all of them, so a page costs one round trip
 * rather than one per owner.
 *
 * <p>No token: the internal prefix is denied at the gateway and permitted in user-service, the
 * arrangement {@link BlockClient} uses. Unlike BlockClient it <b>fails closed</b>, like
 * {@link FriendshipClient}: a lookup that fails answers "follows nobody", which hides a private
 * owner's videos rather than showing them to strangers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowClient {

    /** user-service's cap on one batch — its UserProfileService.MAX_BATCH_IDS. */
    private static final int MAX_IDS_PER_CALL = 100;

    private final RestClient userServiceRestClient;

    public Set<Long> followedAmong(Long followerId, Collection<Long> ownerIds) {
        if (followerId == null || ownerIds.isEmpty()) {
            return Set.of();
        }

        List<Long> ids = new ArrayList<>(ownerIds);
        Set<Long> followed = new HashSet<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_CALL) {
            List<Long> chunk = ids.subList(from, Math.min(from + MAX_IDS_PER_CALL, ids.size()));
            try {
                ApiResponse<List<Long>> response = userServiceRestClient.get()
                        .uri(uri -> uri.path("/api/v1/users/internal/following")
                                .queryParam("followerId", followerId)
                                .queryParam("ids", chunk.toArray())
                                .build())
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {
                        });
                if (response != null && response.data() != null) {
                    followed.addAll(response.data());
                }
            } catch (RestClientException e) {
                log.warn("Could not ask user-service which of {} user {} follows; hiding their videos",
                        chunk, followerId, e);
            }
        }
        return followed;
    }
}
```

- [ ] **Step 4: Decide PUBLIC videos of private owners by following**

In `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java`:

Add the imports `import com.tiktok.videoservice.client.FollowClient;` and `import java.util.stream.Collectors;`.

Add the field after `userSettingsClient`:

```java
    private final FollowClient followClient;
```

At the end of `getByIds`, replace:

```java
        // Filtered after the lookup, not during it, so the cache holds one entry per video
        // rather than one per (video, viewer) — see getById.
        return withAccountComments(wanted.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .filter(video -> isVisibleTo(requesterId, video))
                .toList());
```

with:

```java
        // Filtered after the lookup, not during it, so the cache holds one entry per video
        // rather than one per (video, viewer) — see getById.
        List<VideoResponse> ordered = wanted.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .toList();
        Set<Long> followed = followedPrivateOwners(requesterId, ordered);
        return withAccountComments(ordered.stream()
                .filter(video -> isVisibleTo(requesterId, video, followed))
                .toList());
```

In `requireVisible`, replace:

```java
        if (!isVisibleTo(requesterId, video) || blockClient.isBlockedBetween(video.userId(), requesterId)) {
```

with:

```java
        if (!isVisibleTo(requesterId, video, followedPrivateOwners(requesterId, List.of(video)))
                || blockClient.isBlockedBetween(video.userId(), requesterId)) {
```

Replace the whole `isVisibleTo` method, with its javadoc:

```java
    /**
     * Decided from the response rather than the entity, because by this point the value may have
     * come from Redis and there is no entity. The three fields it reads — userId, status,
     * visibility — are on both, and a video the requester does not own is only theirs to see once
     * it is both PUBLISHED and PUBLIC.
     */
    private boolean isVisibleTo(Long requesterId, VideoResponse video) {
        if (isSelf(requesterId, video.userId())) {
            return true;
        }
        if (video.status() != VideoStatus.PUBLISHED) {
            return false;
        }
        return switch (video.visibility()) {
            case PUBLIC -> true;
            // ponytail: one user-service call per FRIENDS video the requester does not own. PUBLIC
            // videos short-circuit above, so a normal feed/batch makes none; a list that is mostly
            // FRIENDS videos (rare) would. Add a batch friendship endpoint if that ever shows up.
            case FRIENDS -> friendshipClient.areFriends(video.userId(), requesterId);
            case PRIVATE -> false;
        };
    }
```

with:

```java
    /**
     * Decided from the response rather than the entity, because by this point the value may have
     * come from Redis and there is no entity. The fields it reads — userId, status, visibility,
     * ownerPrivate — are on both, and a video the requester does not own is only theirs to see
     * once it is PUBLISHED and its audience takes them in.
     *
     * @param followedPrivateOwners the private owners on this request the requester follows — see
     *                              {@link #followedPrivateOwners}
     */
    private boolean isVisibleTo(Long requesterId, VideoResponse video, Set<Long> followedPrivateOwners) {
        if (isSelf(requesterId, video.userId())) {
            return true;
        }
        if (video.status() != VideoStatus.PUBLISHED) {
            return false;
        }
        return switch (video.visibility()) {
            case PUBLIC -> !video.ownerPrivate() || followedPrivateOwners.contains(video.userId());
            // ponytail: one user-service call per FRIENDS video the requester does not own. PUBLIC
            // videos short-circuit above, so a normal feed/batch makes none; a list that is mostly
            // FRIENDS videos (rare) would. Add a batch friendship endpoint if that ever shows up.
            // A private owner changes nothing here: a mutual follow already implies following.
            case FRIENDS -> friendshipClient.areFriends(video.userId(), requesterId);
            case PRIVATE -> false;
        };
    }

    /**
     * The owners on this request whose PUBLIC videos the requester may see only as a follower,
     * narrowed to the ones they do follow. One call for all of them, and none when nothing on the
     * request belongs to a private owner — every page from accounts nobody made private.
     */
    private Set<Long> followedPrivateOwners(Long requesterId, List<VideoResponse> videos) {
        Set<Long> privateOwners = videos.stream()
                .filter(video -> video.ownerPrivate() && video.visibility() == VideoVisibility.PUBLIC)
                .map(VideoResponse::userId)
                .filter(owner -> !isSelf(requesterId, owner))
                .collect(Collectors.toSet());
        return privateOwners.isEmpty() ? Set.of() : followClient.followedAmong(requesterId, privateOwners);
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -pl services/video-service -am -Dtest='FollowClientTest,VideoServiceImplCacheTest,VideoServiceImplUploadUrlFailureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Run video-service's full suite**

Run: `./mvnw test -pl services/video-service -am`
Expected: BUILD SUCCESS. No existing fixture has a private owner, so no Spring-context test calls the real `FollowClient`.

- [ ] **Step 7: Commit**

```bash
git add services/video-service/src/main/java/com/tiktok/videoservice/client/FollowClient.java \
        services/video-service/src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java \
        services/video-service/src/test/java/com/tiktok/videoservice/client/FollowClientTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java
git commit -m "$(cat <<'EOF'
feat(video): a private owner's videos are for their followers

isVisibleTo lets a private owner's PUBLIC video through only to its owner and
their followers, asked of user-service once per request for every such owner
on it (chunked at 100, failing closed). Public owners never trigger a call.
This covers GET /videos/{id} — the gate interaction-service asks — and the
batch For You and trending are hydrated through.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Close a private owner's grid and keep them off the feeds

**Files:**
- Create: `services/video-service/src/main/java/com/tiktok/videoservice/exception/PrivateAccountException.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/entity/Video.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoService.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java`
- Modify: `services/video-service/src/main/java/com/tiktok/videoservice/controller/VideoController.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/controller/VideoControllerTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java`
- Test: `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java`

**Interfaces:**
- Consumes:
  - Task 7: `OwnerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(Long)` and `findByUserIdInAndPrivateAccountTrue(Collection<Long>)`, plus `OwnerPrivacy.getUserId()`.
  - Task 8: `FollowClient.followedAmong(Long, Collection<Long>)`.
- Produces:
  - `VideoService.getFollowingFeed(Long requesterId, List<Long> followedUserIds, String cursor, Integer size)`.
  - `Video.changeOwnerPrivacy(boolean)`.
  - `VideoRepositoryCustom.updateOwnerPrivacy(Video)`.
  - `PrivateAccountException()` in video-service: 403 `PRIVATE_ACCOUNT`.
  - The `VideoServiceImpl` constructor gains `OwnerPrivacyRepository ownerPrivacyRepository` as its last parameter, after `followClient`.

- [ ] **Step 1: Write the failing service tests**

In `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java`:

Every existing call of `getFollowingFeed` gains a leading `null` requester:

```bash
perl -pi -e 's/videoService\.getFollowingFeed\(/videoService.getFollowingFeed(null, /g' \
  services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java
grep -n "getFollowingFeed(" services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java
```
Expected: 6 lines, each reading `getFollowingFeed(null, `.

Add these imports:

```java
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.exception.PrivateAccountException;
import com.tiktok.videoservice.repository.OwnerPrivacyRepository;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.Set;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
```

Add after the `blockClient` mock:

```java
    // Default answer is an empty set: a private owner's videos stay hidden unless a test says who follows.
    @MockBean
    private com.tiktok.videoservice.client.FollowClient followClient;

    // Spied so one test can make the second read of owner_privacy answer differently from the first.
    @SpyBean
    private OwnerPrivacyRepository ownerPrivacyRepository;
```

Replace `cleanUp()`:

```java
    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
        ownerPrivacyRepository.deleteAll();
    }
```

Append the helper and the tests, inside the class:

```java
    /** What UserPrivacyEventConsumer records; publish reads it to stamp the video. */
    private void makePrivate(long userId) {
        ownerPrivacyRepository.save(new OwnerPrivacy(userId, true, java.time.Instant.now()));
    }

    /** The public feed is for videos anyone may see, so a private owner's never appear on it. */
    @Test
    void getFeed_leavesOutPrivateOwners() {
        makePrivate(1L);
        VideoResponse privateOwners = publishAs(1L, "Private owner", VideoVisibility.PUBLIC);
        markPublished(privateOwners.id());
        VideoResponse publicOwners = publishAs(2L, "Public owner", VideoVisibility.PUBLIC);
        markPublished(publicOwners.id());

        assertThat(videoService.getFeed(null, 10).items()).extracting(VideoResponse::id)
                .containsExactly(publicOwners.id());
    }

    /**
     * The author ids come from the client, so naming a private account must not be a way into its
     * videos. Dropped before the query, so the page and its cursor stay whole.
     */
    @Test
    void getFollowingFeed_dropsPrivateOwnersTheCallerDoesNotFollow() {
        makePrivate(1L);
        makePrivate(2L);
        VideoResponse followed = publishAs(1L, "Followed", VideoVisibility.PUBLIC);
        markPublished(followed.id());
        VideoResponse notFollowed = publishAs(2L, "Not followed", VideoVisibility.PUBLIC);
        markPublished(notFollowed.id());
        VideoResponse publicOwners = publishAs(3L, "Public", VideoVisibility.PUBLIC);
        markPublished(publicOwners.id());
        when(followClient.followedAmong(eq(9L), anyCollection())).thenReturn(Set.of(1L));

        CursorPage<VideoResponse> feed = videoService.getFollowingFeed(9L, List.of(1L, 2L, 3L), null, 10);

        assertThat(feed.items()).extracting(VideoResponse::id)
                .containsExactly(publicOwners.id(), followed.id());
        verify(followClient).followedAmong(9L, Set.of(1L, 2L));
    }

    @Test
    void getFollowingFeed_anonymous_losesEveryPrivateOwner() {
        makePrivate(1L);
        VideoResponse privateOwners = publishAs(1L, "Private", VideoVisibility.PUBLIC);
        markPublished(privateOwners.id());
        VideoResponse publicOwners = publishAs(2L, "Public", VideoVisibility.PUBLIC);
        markPublished(publicOwners.id());

        assertThat(videoService.getFollowingFeed(null, List.of(1L, 2L), null, 10).items())
                .extracting(VideoResponse::id)
                .containsExactly(publicOwners.id());
    }

    /** Nobody follows themselves: a private owner naming their own id still sees their own videos. */
    @Test
    void getFollowingFeed_aPrivateOwnerStillSeesTheirOwn() {
        makePrivate(1L);
        VideoResponse own = publishAs(1L, "Own", VideoVisibility.PUBLIC);
        markPublished(own.id());

        assertThat(videoService.getFollowingFeed(1L, List.of(1L), null, 10).items())
                .extracting(VideoResponse::id)
                .containsExactly(own.id());
        verifyNoInteractions(followClient);
    }

    /** A 403 rather than an empty grid, so the client can say the account is private. */
    @Test
    void listByUser_privateOwner_refusesAStrangerAndServesAFollower() {
        makePrivate(1L);
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());
        when(followClient.followedAmong(2L, List.of(1L))).thenReturn(Set.of(1L));

        assertThat(videoService.listByUser(2L, 1L, PageRequest.of(0, 10)).getContent())
                .extracting(VideoResponse::id)
                .containsExactly(published.id());
        assertThatThrownBy(() -> videoService.listByUser(3L, 1L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
        assertThatThrownBy(() -> videoService.listByUser(null, 1L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
        assertThat(videoService.listByUser(1L, 1L, PageRequest.of(0, 10)).getContent()).hasSize(1);
    }

    /** Counters stay public, as on TikTok: a private account still shows how many videos it has. */
    @Test
    void getUserStats_ofAPrivateOwner_staysPublic() {
        makePrivate(1L);
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());

        assertThat(videoService.getUserStats(null, 1L).videoCount()).isEqualTo(1);
    }

    @Test
    void publish_stampsTheOwnersPrivacy() {
        makePrivate(1L);

        VideoResponse published = publishAs(1L, "Stamped", VideoVisibility.PUBLIC);

        Video stored = videoRepository.findById(published.id()).orElseThrow();
        assertThat(published.ownerPrivate()).isTrue();
        assertThat(stored.isOwnerPrivate()).isTrue();
        // Both reads agreed, so there is nothing extra to announce.
        assertThat(stored.getVisibilityEventPendingAt()).isNull();
    }

    /**
     * The owner goes private between the first read and the insert, and the consumer's fan-out ran
     * before the insert — so it missed this video. Only the second read can catch it.
     */
    @Test
    void publish_whenTheOwnerGoesPrivateMidway_catchesItOnTheSecondRead() {
        doReturn(false).doReturn(true).when(ownerPrivacyRepository).existsByUserIdAndPrivateAccountTrue(1L);

        VideoResponse published = publishAs(1L, "Midway", VideoVisibility.PUBLIC);

        Video stored = videoRepository.findById(published.id()).orElseThrow();
        assertThat(published.ownerPrivate()).isTrue();
        assertThat(stored.isOwnerPrivate()).isTrue();
        assertThat(stored.getVisibilityEventPendingAt()).isNotNull();
    }
```

- [ ] **Step 2: Write the failing controller test, and update the two hand-built services**

Append to `services/video-service/src/test/java/com/tiktok/videoservice/controller/VideoControllerTest.java`, inside the class:

```java
    /**
     * The caller decides which private owners stay on the page. A controller that stopped
     * forwarding it would serve everyone the anonymous feed, private followed accounts missing.
     */
    @Test
    void getFollowingFeed_passesTheCaller() throws Exception {
        when(videoService.getFollowingFeed(any(), any(), any(), any())).thenReturn(new CursorPage<>(List.of(), null));

        mockMvc.perform(get("/api/v1/videos/feed/following").param("followedUserIds", "1,2")
                        .header("Authorization", "Bearer " + tokenFor(5L)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/videos/feed/following").param("followedUserIds", "1,2"))
                .andExpect(status().isOk());

        verify(videoService).getFollowingFeed(eq(5L), eq(List.of(1L, 2L)), isNull(), isNull());
        verify(videoService).getFollowingFeed(isNull(), eq(List.of(1L, 2L)), isNull(), isNull());
    }
```

In `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java`, replace:

```java
                userSettingsClient,
                followClient);
```

with:

```java
                userSettingsClient,
                followClient,
                mock(com.tiktok.videoservice.repository.OwnerPrivacyRepository.class));
```

In `services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java`, replace:

```java
                mock(com.tiktok.videoservice.client.FollowClient.class));
```

with:

```java
                mock(com.tiktok.videoservice.client.FollowClient.class),
                mock(com.tiktok.videoservice.repository.OwnerPrivacyRepository.class));
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw test -pl services/video-service -am -Dtest='VideoServiceImplTest,VideoControllerTest,VideoServiceImplCacheTest,VideoServiceImplUploadUrlFailureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error: the four-argument `getFollowingFeed`, `PrivateAccountException` and the widened `VideoServiceImpl` constructor do not exist yet.

- [ ] **Step 4: Add the exception and the per-video rewrite**

Create `services/video-service/src/main/java/com/tiktok/videoservice/exception/PrivateAccountException.java`:

```java
package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The profile grid of a private account, asked for by someone who neither owns nor follows it. A
 * 403 rather than the 404 a hidden video gets: the account's privacy is already on its profile,
 * so the status gives nothing away.
 */
public class PrivateAccountException extends ForbiddenException {

    public PrivateAccountException() {
        super("PRIVATE_ACCOUNT", "This account is private");
    }
}
```

In `services/video-service/src/main/java/com/tiktok/videoservice/entity/Video.java`, add after `changeCommentsDisabled`:

```java
    /**
     * The owner's account switched while this video was being published — see
     * VideoServiceImpl.publish. Queues the announcement like {@link #changeVisibility}, since the
     * audience the read paths hold follows from this flag too.
     */
    public void changeOwnerPrivacy(boolean ownerPrivate) {
        this.ownerPrivate = ownerPrivate;
        this.visibilityEventPendingAt = Instant.now();
    }
```

In `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java`:

Add after `markOwnerPrivacy`:

```java
    /**
     * One video's copy of its owner's privacy, found stale by the second read in
     * {@code VideoServiceImpl.publish}. Both fields in one write, like {@link #updateVisibility}.
     */
    void updateOwnerPrivacy(Video video);
```

In the javadoc of `findFeedPage`, replace:

```java
     * @param userIds         restrict to these authors — the Following feed — or null for the
     *                        public feed, which is every author
```

with:

```java
     * @param userIds         restrict to these authors — the Following feed — or null for the
     *                        public feed, which is every author whose account is not private
```

In `services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java`:

In `findFeedPage`, replace:

```java
        if (userIds != null) {
            criteria = criteria.and("userId").in(userIds);
        }
```

with:

```java
        if (userIds != null) {
            criteria = criteria.and("userId").in(userIds);
        } else {
            // The public feed is for videos anyone may see, so a private owner's never appear on
            // it — not even for their followers, who get them on the Following feed instead.
            // ponytail: a residual filter after feed_idx, so a private owner's videos are read and
            // skipped; add ownerPrivate to feed_idx if they ever become a large share of the feed.
            criteria = criteria.and("ownerPrivate").ne(true);
        }
```

Add after `markOwnerPrivacy`:

```java
    @Override
    public void updateOwnerPrivacy(Video video) {
        update(video.getId(), new Update()
                .set("ownerPrivate", video.isOwnerPrivate())
                .set("visibilityEventPendingAt", video.getVisibilityEventPendingAt()));
    }
```

- [ ] **Step 5: Give the Following feed its caller**

In `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoService.java`, replace:

```java
     * read path it has, and the visibility rule is unchanged either way: only PUBLISHED and PUBLIC
     * videos come back, so naming an author the caller does not actually follow reveals nothing
     * that {@code /videos/users/{id}} would not.
     *
     * @param followedUserIds the authors to draw from; empty or null is an empty page, not the
```

with:

```java
     * read path it has, and the visibility rule is unchanged either way: only PUBLISHED and PUBLIC
     * videos come back, so naming an author the caller does not actually follow reveals nothing
     * that {@code /videos/users/{id}} would not. The exception is a private account, which is
     * dropped from the ids unless the requester is it or follows it.
     *
     * @param requesterId     the caller, or null when anonymous — who loses every private account
     * @param followedUserIds the authors to draw from; empty or null is an empty page, not the
```

and replace:

```java
    CursorPage<VideoResponse> getFollowingFeed(List<Long> followedUserIds, String cursor, Integer size);
```

with:

```java
    CursorPage<VideoResponse> getFollowingFeed(
            Long requesterId, List<Long> followedUserIds, String cursor, Integer size);
```

In `services/video-service/src/main/java/com/tiktok/videoservice/controller/VideoController.java`, replace:

```java
     * <p>Naming an account the caller does not follow is not a way in: the filter below it is the
     * public one, so this returns exactly what {@code /videos/users/{id}} would already hand out.
     */
    @GetMapping("/feed/following")
    public ApiResponse<CursorPage<VideoResponse>> getFollowingFeed(
            @RequestParam(required = false) List<Long> followedUserIds,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(videoService.getFollowingFeed(followedUserIds, cursor, size));
    }
```

with:

```java
     * <p>Naming an account the caller does not follow is not a way in: the filter below it is the
     * public one, and a private account among the ids is dropped unless the caller follows it —
     * which is why the caller is passed along, and why a signed-in client sends its token.
     */
    @GetMapping("/feed/following")
    public ApiResponse<CursorPage<VideoResponse>> getFollowingFeed(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) List<Long> followedUserIds,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(
                videoService.getFollowingFeed(currentUserId, followedUserIds, cursor, size));
    }
```

- [ ] **Step 6: Read owner_privacy on publish, on the grid and on the Following feed**

In `services/video-service/src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java`:

Add these imports:

```java
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.exception.PrivateAccountException;
import com.tiktok.videoservice.repository.OwnerPrivacyRepository;
```

Add the field after `followClient`:

```java
    private final OwnerPrivacyRepository ownerPrivacyRepository;
```

Replace the whole `publish` method:

```java
    @Override
    public VideoResponse publish(Long userId, CreateVideoRequest request) {
        String rawFileUrl = storedUrl(requireOwnUpload(userId, request.rawFileUrl()));

        Video video = Video.builder()
                .id(Video.newId())
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .rawFileUrl(rawFileUrl)
                .visibility(request.visibility())
                .tags(normalizeTags(request.tags(), request.description()))
                .status(VideoStatus.PROCESSING)
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();

        try {
            return withAccountComments(videoMapper.toResponse(videoRepository.save(video)));
        } catch (DuplicateKeyException e) {
            // raw_file_idx. The only unique index on this collection besides _id, and _id is a
            // fresh Snowflake generated a few lines above. rawFileUrl is rebuilt from the key, so
            // two spellings of one object (s3:// and the CDN URL, say) collide here too.
            throw new AlreadyPublishedException();
        }
    }
```

with:

```java
    @Override
    public VideoResponse publish(Long userId, CreateVideoRequest request) {
        String rawFileUrl = storedUrl(requireOwnUpload(userId, request.rawFileUrl()));

        Video video = Video.builder()
                .id(Video.newId())
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .rawFileUrl(rawFileUrl)
                .visibility(request.visibility())
                .ownerPrivate(isPrivateOwner(userId))
                .tags(normalizeTags(request.tags(), request.description()))
                .status(VideoStatus.PROCESSING)
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();

        try {
            videoRepository.save(video);
        } catch (DuplicateKeyException e) {
            // raw_file_idx. The only unique index on this collection besides _id, and _id is a
            // fresh Snowflake generated a few lines above. rawFileUrl is rebuilt from the key, so
            // two spellings of one object (s3:// and the CDN URL, say) collide here too.
            throw new AlreadyPublishedException();
        }

        // Read again after the insert. UserPrivacyEventConsumer records a switch and then stamps
        // every video the owner has: if its stamping ran before this insert, its recording did too
        // and this read sees it; if after, it rewrote this video with the rest. With the first
        // read alone, a switch landing between it and the insert would leave this video wrong for
        // good.
        boolean ownerPrivate = isPrivateOwner(userId);
        if (ownerPrivate != video.isOwnerPrivate()) {
            video.changeOwnerPrivacy(ownerPrivate);
            videoRepository.updateOwnerPrivacy(video);
        }
        return withAccountComments(videoMapper.toResponse(video));
    }
```

Replace the whole `getFollowingFeed` method:

```java
    @Override
    public CursorPage<VideoResponse> getFollowingFeed(
            List<Long> followedUserIds, String cursor, Integer size) {
        // Following nobody is an empty tab, not the public feed. Returning early also keeps an
        // empty $in — which matches nothing but still costs a query — off the wire.
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return new CursorPage<>(List.of(), null);
        }
        if (followedUserIds.size() > MAX_FOLLOWED_USERS) {
            throw new TooManyFollowedUsersException(MAX_FOLLOWED_USERS);
        }

        // Deduplicated: the caller assembles this from a paged listing, and a repeat id widens the
        // $in for nothing.
        return feedPage(new LinkedHashSet<>(followedUserIds), cursor, size);
    }
```

with:

```java
    @Override
    public CursorPage<VideoResponse> getFollowingFeed(
            Long requesterId, List<Long> followedUserIds, String cursor, Integer size) {
        // Following nobody is an empty tab, not the public feed. Returning early also keeps an
        // empty $in — which matches nothing but still costs a query — off the wire.
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return new CursorPage<>(List.of(), null);
        }
        if (followedUserIds.size() > MAX_FOLLOWED_USERS) {
            throw new TooManyFollowedUsersException(MAX_FOLLOWED_USERS);
        }

        // Deduplicated: the caller assembles this from a paged listing, and a repeat id widens the
        // $in for nothing.
        Set<Long> authors = new LinkedHashSet<>(followedUserIds);
        // The ids come from the client, so naming a private account is not the same as following
        // it. Dropped before the query rather than filtered out of the page, which would come back
        // short under a cursor that already went past what it hid.
        authors.removeAll(privateOwnersHiddenFrom(requesterId, authors));
        if (authors.isEmpty()) {
            return new CursorPage<>(List.of(), null);
        }
        return feedPage(authors, cursor, size);
    }
```

Replace the javadoc and the opening of `listByUser`:

```java
    /**
     * The owner sees their whole shelf; anyone else sees only what is actually published and
     * public — the same line {@link #getById} draws for a single video, applied to the list.
     * The endpoint takes no token, so requesterId is null for anonymous callers and they fall
     * to the filtered branch.
     */
    @Override
    public Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable) {
        if (isSelf(requesterId, userId)) {
            return withAccountComments(
                    videoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                            .map(videoMapper::toResponse)
                            .map(quarantinedMediaUrls::forOwner));
        }
```

with:

```java
    /**
     * The owner sees their whole shelf; anyone else sees only what is actually published and
     * public — the same line {@link #getById} draws for a single video, applied to the list.
     * The endpoint takes no token, so requesterId is null for anonymous callers and they fall
     * to the filtered branch.
     *
     * <p>A private account's grid is for its followers, and anyone else is told so with a 403
     * rather than an empty page — the client shows "This account is private", not "No videos yet".
     * Asked of owner_privacy rather than of the videos, so an account with nothing on its grid
     * answers the same way.
     */
    @Override
    public Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable) {
        if (isSelf(requesterId, userId)) {
            return withAccountComments(
                    videoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                            .map(videoMapper::toResponse)
                            .map(quarantinedMediaUrls::forOwner));
        }

        if (isPrivateOwner(userId)
                && !followClient.followedAmong(requesterId, List.of(userId)).contains(userId)) {
            throw new PrivateAccountException();
        }
```

Add before `isOwner`:

```java
    private boolean isPrivateOwner(Long userId) {
        return ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(userId);
    }

    /**
     * The private accounts among {@code owners} whose videos {@code requesterId} may not see:
     * neither theirs nor followed. One owner_privacy query, and a follow lookup only when one of
     * them is private.
     */
    private Set<Long> privateOwnersHiddenFrom(Long requesterId, Collection<Long> owners) {
        Set<Long> privateOwners = ownerPrivacyRepository.findByUserIdInAndPrivateAccountTrue(owners).stream()
                .map(OwnerPrivacy::getUserId)
                .filter(owner -> !isSelf(requesterId, owner))
                .collect(Collectors.toSet());
        if (privateOwners.isEmpty()) {
            return privateOwners;
        }
        Set<Long> followed = followClient.followedAmong(requesterId, privateOwners);
        return privateOwners.stream()
                .filter(owner -> !followed.contains(owner))
                .collect(Collectors.toSet());
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -pl services/video-service -am -Dtest='VideoServiceImplTest,VideoControllerTest,VideoServiceImplCacheTest,VideoServiceImplUploadUrlFailureTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 8: Run video-service's full suite**

Run: `./mvnw test -pl services/video-service -am`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add services/video-service/src/main/java/com/tiktok/videoservice/exception/PrivateAccountException.java \
        services/video-service/src/main/java/com/tiktok/videoservice/entity/Video.java \
        services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryCustom.java \
        services/video-service/src/main/java/com/tiktok/videoservice/repository/VideoRepositoryImpl.java \
        services/video-service/src/main/java/com/tiktok/videoservice/service/VideoService.java \
        services/video-service/src/main/java/com/tiktok/videoservice/service/VideoServiceImpl.java \
        services/video-service/src/main/java/com/tiktok/videoservice/controller/VideoController.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/controller/VideoControllerTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplCacheTest.java \
        services/video-service/src/test/java/com/tiktok/videoservice/service/VideoServiceImplUploadUrlFailureTest.java
git commit -m "$(cat <<'EOF'
feat(video): close a private owner's grid and keep them off the feeds

The profile grid answers 403 PRIVATE_ACCOUNT to anyone but the owner and
their followers. The newest feed leaves private owners out, and the
Following feed takes the caller and drops the private owners it does not
follow, since the author ids come from the client. Publishing stamps
ownerPrivate from owner_privacy and reads it again after the insert, so a
switch landing mid-publish is not missed. Stats stay public.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: A private account's reposts are for its followers

**Files:**
- Create: `services/interaction-service/src/main/java/com/tiktok/interactionservice/exception/PrivateAccountException.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/client/FriendshipClient.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/RepostService.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/RepostServiceImpl.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/controller/UserInteractionController.java`
- Test (new): `services/interaction-service/src/test/java/com/tiktok/interactionservice/client/FriendshipClientTest.java`
- Test (new): `services/interaction-service/src/test/java/com/tiktok/interactionservice/controller/UserInteractionControllerTest.java`
- Test: `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/RepostServiceImplTest.java`

**Interfaces:**
- Consumes:
  - Task 5: `GET /api/v1/users/internal/friendship?userB={owner}[&userA={viewer}]`. It answers a `FriendshipResponse` carrying `following` and `privateAccount`.
- Produces:
  - `boolean FriendshipClient.canSeeFollowerContent(Long ownerId, Long viewerId)`. It fails closed.
  - `RepostService.listReposts(Long viewerId, Long userId, String cursor, int size)`.
  - `PrivateAccountException()` in interaction-service: 403 `PRIVATE_ACCOUNT`.

- [ ] **Step 1: Write the failing tests**

Create `services/interaction-service/src/test/java/com/tiktok/interactionservice/client/FriendshipClientTest.java`:

```java
package com.tiktok.interactionservice.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FriendshipClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://user-service");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final FriendshipClient client = new FriendshipClient(builder.build());

    /** A public account's tab is anyone's, the anonymous caller's included — asked without a viewer. */
    @Test
    void canSeeFollowerContent_publicAccount_isOpenToAnyone() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"userId": 7, "friends": false, "following": false, "requested": false, "privateAccount": false}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.canSeeFollowerContent(7L, null)).isTrue();
        server.verify();
    }

    @Test
    void canSeeFollowerContent_privateAccount_onlyForAFollower() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7&userA=5"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"following": true, "privateAccount": true}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7&userA=6"))
                .andRespond(withSuccess("""
                        {"success": true, "data": {"following": false, "privateAccount": true}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.canSeeFollowerContent(7L, 5L)).isTrue();
        assertThat(client.canSeeFollowerContent(7L, 6L)).isFalse();
        server.verify();
    }

    /** Fails closed: a lookup that failed must not open a private account's tab. */
    @Test
    void canSeeFollowerContent_whenUserServiceFails_isNo() {
        server.expect(requestTo("http://user-service/api/v1/users/internal/friendship?userB=7&userA=5"))
                .andRespond(withServerError());

        assertThat(client.canSeeFollowerContent(7L, 5L)).isFalse();
        server.verify();
    }
}
```

Create `services/interaction-service/src/test/java/com/tiktok/interactionservice/controller/UserInteractionControllerTest.java`:

```java
package com.tiktok.interactionservice.controller;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.exception.PrivateAccountException;
import com.tiktok.interactionservice.service.RepostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UserInteractionControllerTest extends AbstractInteractionServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RepostService repostService;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String tokenFor(long userId) {
        return new JwtProvider(jwtSecret).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    /** The token is how a follower is told from a stranger, so the caller has to reach the service. */
    @Test
    void reposts_passTheCallerWhenThereIsOne() throws Exception {
        when(repostService.listReposts(any(), eq(7L), any(), anyInt()))
                .thenReturn(new VideoIdPageResponse(List.of(), null, false));

        mockMvc.perform(get("/api/v1/interactions/users/7/reposts")
                        .header("Authorization", "Bearer " + tokenFor(5L)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/interactions/users/7/reposts"))
                .andExpect(status().isOk());

        verify(repostService).listReposts(5L, 7L, null, 20);
        verify(repostService).listReposts(null, 7L, null, 20);
    }

    @Test
    void reposts_ofAPrivateAccount_are403ToAStranger() throws Exception {
        when(repostService.listReposts(any(), eq(7L), any(), anyInt())).thenThrow(new PrivateAccountException());

        mockMvc.perform(get("/api/v1/interactions/users/7/reposts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVATE_ACCOUNT"));
    }
}
```

In `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/RepostServiceImplTest.java`:

Every existing call of `listReposts` is the owner reading their own tab, so it gains the owner as the viewer:

```bash
perl -pi -e 's/repostService\.listReposts\(1L, /repostService.listReposts(1L, 1L, /g' \
  services/interaction-service/src/test/java/com/tiktok/interactionservice/service/RepostServiceImplTest.java
grep -n "listReposts(" services/interaction-service/src/test/java/com/tiktok/interactionservice/service/RepostServiceImplTest.java
```
Expected: 6 lines, each reading `listReposts(1L, 1L, `.

Add these imports:

```java
import com.tiktok.interactionservice.client.FriendshipClient;
import com.tiktok.interactionservice.exception.PrivateAccountException;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
```

Add the field:

```java
    // Default answer false: anyone but the owner is refused unless a test says they may look.
    @MockBean
    private FriendshipClient friendshipClient;
```

Append inside the class:

```java
    /**
     * A private account's tab is for its owner and followers. user-service is asked about anyone
     * but the owner — the anonymous caller included, as the question's null viewer.
     */
    @Test
    void listReposts_ofAPrivateAccount_isForItsOwnerAndFollowersOnly() {
        repostService.repost(19L, 1L);
        when(friendshipClient.canSeeFollowerContent(1L, 2L)).thenReturn(true);

        assertThat(repostService.listReposts(1L, 1L, null, 20).videoIds()).containsExactly(19L);
        assertThat(repostService.listReposts(2L, 1L, null, 20).videoIds()).containsExactly(19L);
        assertThatThrownBy(() -> repostService.listReposts(3L, 1L, null, 20))
                .isInstanceOf(PrivateAccountException.class);
        assertThatThrownBy(() -> repostService.listReposts(null, 1L, null, 20))
                .isInstanceOf(PrivateAccountException.class);
        verify(friendshipClient, never()).canSeeFollowerContent(1L, 1L);
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -pl services/interaction-service -am -Dtest='FriendshipClientTest,UserInteractionControllerTest,RepostServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `canSeeFollowerContent`, `PrivateAccountException` and the four-argument `listReposts`.

- [ ] **Step 3: Ask whether the viewer may see a follower-only tab**

Create `services/interaction-service/src/main/java/com/tiktok/interactionservice/exception/PrivateAccountException.java`:

```java
package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The Reposts tab of a private account, asked for by someone who neither owns nor follows it.
 * A 403 with its own code, so the client can say "This account is private" instead of an empty tab.
 */
public class PrivateAccountException extends ForbiddenException {

    public PrivateAccountException() {
        super("PRIVATE_ACCOUNT", "This account is private");
    }
}
```

In `services/interaction-service/src/main/java/com/tiktok/interactionservice/client/FriendshipClient.java`:

Add the import `import java.util.Optional;`.

In the class javadoc, add before the closing `*/`:

```java
 *
 * <p>{@link #canSeeFollowerContent} is the exception, and asks the tokenless internal endpoint:
 * the Reposts tab is public, so its caller may be anonymous with no token to forward.
```

Replace:

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FriendshipView(boolean friends) {
    }
```

with:

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FriendshipView(boolean friends, boolean following, boolean privateAccount) {
    }
```

Add after `areFriends`:

```java
    /**
     * Whether {@code viewerId} may see what {@code ownerId} keeps for followers: true for a public
     * account, and for a private one the viewer follows. {@code viewerId} is null for an anonymous
     * caller. Fails closed like {@link #areFriends}: an answer nobody could give reads as a private
     * account the viewer does not follow.
     */
    public boolean canSeeFollowerContent(Long ownerId, Long viewerId) {
        try {
            ApiResponse<FriendshipView> response = userServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/friendship")
                            .queryParam("userB", ownerId)
                            .queryParamIfPresent("userA", Optional.ofNullable(viewerId))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.data() == null) {
                return false;
            }
            return !response.data().privateAccount() || response.data().following();
        } catch (RestClientException e) {
            log.warn("Could not ask whether user {} is private or followed by {}; hiding their reposts",
                    ownerId, viewerId, e);
            return false;
        }
    }
```

- [ ] **Step 4: Gate the tab**

In `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/RepostService.java`, replace:

```java
    VideoIdPageResponse listReposts(Long currentUserId, String cursor, int size);
```

with:

```java
    /**
     * The reposts on {@code userId}'s profile. A private account's are for its owner and followers:
     * anyone else — a null {@code viewerId}, the anonymous caller, included — gets PRIVATE_ACCOUNT.
     */
    VideoIdPageResponse listReposts(Long viewerId, Long userId, String cursor, int size);
```

In `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/RepostServiceImpl.java`:

Add the imports `import com.tiktok.interactionservice.client.FriendshipClient;` and `import com.tiktok.interactionservice.exception.PrivateAccountException;`.

Add the field after `videoOwnershipClient`:

```java
    private final FriendshipClient friendshipClient;
```

Replace the whole `listReposts` method:

```java
    @Override
    public VideoIdPageResponse listReposts(Long currentUserId, String cursor, int size) {
        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, InvalidCursorException::new);
        try {
            Slice<RepostByUser> slice = repostByUserRepository.findByUserId(currentUserId, pageRequest);
            return CassandraCursors.page(slice, repost -> repost.getKey().getVideoId());
        } catch (CassandraInvalidQueryException e) {
            throw new InvalidCursorException();
        }
    }
```

with:

```java
    @Override
    public VideoIdPageResponse listReposts(Long viewerId, Long userId, String cursor, int size) {
        // Refused outright rather than answered empty, so the client can say why.
        if (!userId.equals(viewerId) && !friendshipClient.canSeeFollowerContent(userId, viewerId)) {
            throw new PrivateAccountException();
        }

        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, InvalidCursorException::new);
        try {
            Slice<RepostByUser> slice = repostByUserRepository.findByUserId(userId, pageRequest);
            return CassandraCursors.page(slice, repost -> repost.getKey().getVideoId());
        } catch (CassandraInvalidQueryException e) {
            throw new InvalidCursorException();
        }
    }
```

In `services/interaction-service/src/main/java/com/tiktok/interactionservice/controller/UserInteractionController.java`:

In the class javadoc, replace:

```java
 * the exception: reposting is a public act, shown on the reposter's profile to everyone, so that
 * listing takes a {userId} and needs no token.
```

with:

```java
 * the exception: reposting is a public act, shown on the reposter's profile to everyone, so that
 * listing takes a {userId} and needs no token — unless the account is private, when the token is
 * how a follower is told from a stranger.
```

Replace:

```java
    /** Public: anyone opening a profile's Reposts tab sees the same list its owner does. */
    @GetMapping("/{userId}/reposts")
    public ApiResponse<VideoIdPageResponse> listRepostedVideos(
            @PathVariable Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                repostService.listReposts(userId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
```

with:

```java
    /**
     * Public for a public account: anyone opening the Reposts tab sees what its owner sees. A
     * private account's is for its owner and followers — 403 PRIVATE_ACCOUNT to anyone else.
     */
    @GetMapping("/{userId}/reposts")
    public ApiResponse<VideoIdPageResponse> listRepostedVideos(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(repostService.listReposts(
                currentUserId, userId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
```

`SecurityConfig` needs no change: `GET /api/v1/interactions/users/*/reposts` stays `permitAll`, and the JWT filter still binds a token when one is sent.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -pl services/interaction-service -am -Dtest='FriendshipClientTest,UserInteractionControllerTest,RepostServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Run interaction-service's full suite**

Run: `./mvnw test -pl services/interaction-service -am`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add services/interaction-service/src/main/java/com/tiktok/interactionservice/exception/PrivateAccountException.java \
        services/interaction-service/src/main/java/com/tiktok/interactionservice/client/FriendshipClient.java \
        services/interaction-service/src/main/java/com/tiktok/interactionservice/service/RepostService.java \
        services/interaction-service/src/main/java/com/tiktok/interactionservice/service/RepostServiceImpl.java \
        services/interaction-service/src/main/java/com/tiktok/interactionservice/controller/UserInteractionController.java \
        services/interaction-service/src/test/java/com/tiktok/interactionservice/client/FriendshipClientTest.java \
        services/interaction-service/src/test/java/com/tiktok/interactionservice/controller/UserInteractionControllerTest.java \
        services/interaction-service/src/test/java/com/tiktok/interactionservice/service/RepostServiceImplTest.java
git commit -m "$(cat <<'EOF'
feat(interaction): a private account's reposts are for its followers

The Reposts tab takes the optional caller and asks user-service's internal
friendship endpoint whether the owner is private and followed; anyone but the
owner and their followers gets 403 PRIVATE_ACCOUNT. A failed lookup is
treated as private and not followed.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: Tell both sides about follow requests

**Files:**
- Create: `services/notification-service/src/main/java/com/tiktok/notificationservice/event/consumer/FollowRequestEventConsumer.java`
- Modify: `services/notification-service/src/main/java/com/tiktok/notificationservice/entity/NotificationType.java`
- Modify: `services/notification-service/src/main/java/com/tiktok/notificationservice/event/consumer/FollowEventConsumer.java`
- Test (new): `services/notification-service/src/test/java/com/tiktok/notificationservice/event/consumer/FollowRequestEventConsumerTest.java`
- Test (new): `services/notification-service/src/test/java/com/tiktok/notificationservice/entity/NotificationTypeTest.java`
- Test: `services/notification-service/src/test/java/com/tiktok/notificationservice/event/consumer/FollowEventConsumerTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `FollowRequestedEvent(eventId, occurredAt, requesterId, targetId)` on `user.follow-request-events`.
  - Task 1: `UserFollowChangedEvent.requestApproved()` on `user.follow-events`.
- Produces:
  - `NotificationType.FOLLOW_REQUEST`: the recipient is the target, and the actor and reference are the requester.
  - `NotificationType.FOLLOW_REQUEST_ACCEPTED`: the recipient is the requester, and the actor and reference are the account now followed.

- [ ] **Step 1: Write the failing tests**

Create `services/notification-service/src/test/java/com/tiktok/notificationservice/entity/NotificationTypeTest.java`:

```java
package com.tiktok.notificationservice.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTypeTest {

    /** Asking, withdrawing and asking again is one request to the owner, not three inbox entries. */
    @Test
    void followRequestsCollapseRepeatsLikeFollows() {
        assertThat(NotificationType.FOLLOW_REQUEST.collapsesRepeats()).isTrue();
        assertThat(NotificationType.FOLLOW_REQUEST_ACCEPTED.collapsesRepeats()).isTrue();
    }
}
```

Create `services/notification-service/src/test/java/com/tiktok/notificationservice/event/consumer/FollowRequestEventConsumerTest.java`:

```java
package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.user.FollowRequestedEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowRequestEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** The deep link opens the requester's profile, where the owner decides. */
    @Test
    void onMessage_tellsTheOwnerWhoAsked() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        new FollowRequestEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper)
                .onMessage(objectMapper.writeValueAsString(FollowRequestedEvent.of(9L, 50L)));

        verify(notificationService).create(
                eq(50L), eq(9L), eq(NotificationType.FOLLOW_REQUEST), any(), any(), eq("9"));
    }
}
```

Append to `services/notification-service/src/test/java/com/tiktok/notificationservice/event/consumer/FollowEventConsumerTest.java`, inside the class:

```java
    /** The owner approved it themselves, so the requester is the one told — and the owner is not. */
    @Test
    void onMessage_anApprovedRequest_tellsTheRequesterAndNotTheOwner() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, true, true)));

        verify(notificationService).create(
                eq(9L), eq(50L), eq(NotificationType.FOLLOW_REQUEST_ACCEPTED), any(), any(), eq("50"));
        verify(notificationService, never()).create(
                any(), any(), eq(NotificationType.NEW_FOLLOWER), any(), any(), any());
    }

    /** Events written before requestApproved existed carry no such key, and read as a plain follow. */
    @Test
    void onMessage_aPayloadFromBeforeFollowRequests_isAPlainFollow() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        consumer().onMessage("""
                {"eventId": "e-1", "occurredAt": "2026-09-01T10:00:00Z", "followerId": 9, "followingId": 50, "followed": true}
                """);

        verify(notificationService).create(
                eq(50L), eq(9L), eq(NotificationType.NEW_FOLLOWER), any(), any(), eq("9"));
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw test -pl services/notification-service -am -Dtest='NotificationTypeTest,FollowRequestEventConsumerTest,FollowEventConsumerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — compilation error, `cannot find symbol` for `FOLLOW_REQUEST`, `FOLLOW_REQUEST_ACCEPTED` and `FollowRequestEventConsumer`.

- [ ] **Step 3: Add the two types**

Replace the whole of `services/notification-service/src/main/java/com/tiktok/notificationservice/entity/NotificationType.java`:

```java
package com.tiktok.notificationservice.entity;

public enum NotificationType {
    LIKE,
    COMMENT,
    COMMENT_LIKE,
    SHARE,
    NEW_FOLLOWER,
    /** Someone asked to follow the recipient's private account; actor and reference are the requester. */
    FOLLOW_REQUEST,
    /** The recipient's follow request was approved; actor and reference are the account now followed. */
    FOLLOW_REQUEST_ACCEPTED,
    SYSTEM;

    /**
     * True for the types whose event is a flag being flipped rather than a piece of content.
     * Un-liking and re-liking the same video emits a fresh event with a fresh eventId every
     * time, so idempotency on the event cannot help: to the inbox they are distinct likes, and
     * the owner ends up with one entry per toggle for a single real admirer. Asking to follow,
     * withdrawing and asking again is the same story.
     *
     * <p>COMMENT is excluded — two comments by the same person on the same video are two things
     * worth being told about, and they share a referenceId (the video), so collapsing them would
     * hide the second. SYSTEM has no actor to collapse against.
     */
    public boolean collapsesRepeats() {
        return this == LIKE || this == COMMENT_LIKE || this == SHARE || this == NEW_FOLLOWER
                || this == FOLLOW_REQUEST || this == FOLLOW_REQUEST_ACCEPTED;
    }
}
```

- [ ] **Step 4: Consume the requests and the approvals**

Create `services/notification-service/src/main/java/com/tiktok/notificationservice/event/consumer/FollowRequestEventConsumer.java`:

```java
package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.FollowRequestedEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Tells the owner of a private account that someone asked to follow them. Like a follow, the event
 * names both accounts, so there is nothing to look up.
 *
 * <p>ponytail: a request that is withdrawn or rejected leaves this notification in the inbox. The
 * requests list is the authority; retracting it would need a delete path this service does not
 * have.
 */
@Component
@RequiredArgsConstructor
public class FollowRequestEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.follow-request-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload) {
        FollowRequestedEvent event = objectMapper.readValue(payload, FollowRequestedEvent.class);

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () ->
                notificationService.create(
                        event.targetId(),
                        event.requesterId(),
                        NotificationType.FOLLOW_REQUEST,
                        "Follow request",
                        "Someone asked to follow you.",
                        // The requester's profile, like NEW_FOLLOWER: where the owner goes to decide.
                        String.valueOf(event.requesterId())));
    }
}
```

Replace the whole of `services/notification-service/src/main/java/com/tiktok/notificationservice/event/consumer/FollowEventConsumer.java`:

```java
package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The one stream that needs no lookup: a follow event names both accounts, so the recipient is
 * already in the payload.
 */
@Component
@RequiredArgsConstructor
public class FollowEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.follow-events", groupId = "notification-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserFollowChangedEvent event = objectMapper.readValue(payload, UserFollowChangedEvent.class);

        // An unfollow is not announced, same as an unlike.
        if (!event.followed() || event.followerId().equals(event.followingId())) {
            return;
        }

        idempotentEventProcessor.runOnce(event.eventId(), event.getClass().getSimpleName(), () -> {
            if (event.requestApproved()) {
                // The owner approved it themselves, so they are not told; the requester is.
                notificationService.create(
                        event.followerId(),
                        event.followingId(),
                        NotificationType.FOLLOW_REQUEST_ACCEPTED,
                        "Follow request accepted",
                        "Your follow request was accepted.",
                        String.valueOf(event.followingId()));
                return;
            }

            notificationService.create(
                    event.followingId(),
                    event.followerId(),
                    NotificationType.NEW_FOLLOWER,
                    "New follower",
                    "Someone started following you.",
                    // The follower, not a video: this is the one type whose deep link goes to
                    // a profile.
                    String.valueOf(event.followerId()));
        });
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -pl services/notification-service -am -Dtest='NotificationTypeTest,FollowRequestEventConsumerTest,FollowEventConsumerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Run notification-service's full suite**

Run: `./mvnw test -pl services/notification-service -am`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add services/notification-service/src/main/java/com/tiktok/notificationservice/event/consumer/FollowRequestEventConsumer.java \
        services/notification-service/src/main/java/com/tiktok/notificationservice/entity/NotificationType.java \
        services/notification-service/src/main/java/com/tiktok/notificationservice/event/consumer/FollowEventConsumer.java \
        services/notification-service/src/test/java/com/tiktok/notificationservice/event/consumer/FollowRequestEventConsumerTest.java \
        services/notification-service/src/test/java/com/tiktok/notificationservice/entity/NotificationTypeTest.java \
        services/notification-service/src/test/java/com/tiktok/notificationservice/event/consumer/FollowEventConsumerTest.java
git commit -m "$(cat <<'EOF'
feat(notification): tell both sides about follow requests

FOLLOW_REQUEST goes to a private account's owner when someone asks, and
FOLLOW_REQUEST_ACCEPTED to the requester when the owner approves — instead
of the NEW_FOLLOWER the owner would otherwise get for their own approval.
Both collapse repeats like NEW_FOLLOWER. Old follow payloads without
requestApproved read as plain follows.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Document private accounts across the API contracts

**Files:**
- Modify: `docs/user-service-api.md`
- Modify: `docs/video-service-api.md`
- Modify: `docs/interaction-service-api.md`
- Modify: `docs/user-settings.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/superpowers/specs/2026-09-26-private-account-design.md` (status line)

**Interfaces:**
- Consumes: the contracts Tasks 1–11 built.
- Produces: nothing code depends on.

The three `*-service-api.md` documents are written in Vietnamese. New text in them stays Vietnamese; code names, codes and paths stay as they are.

- [ ] **Step 1: `docs/user-service-api.md` — profiles, follow, lists and settings**

In §3.1, replace:

~~~
  "followerCount": 10,
  "followingCount": 5
}
```
~~~

with:

~~~
  "followerCount": 10,
  "followingCount": 5,
  "privateAccount": false
}
```

`privateAccount = true`: tài khoản riêng tư — chỉ người đã được duyệt follow mới xem được video, danh sách followers/following và tab Reposts (mục 3.16). Profile và các con số (followers, following, likes, số video) vẫn công khai như TikTok. Field này có trên **mọi** `UserProfileResponse` (mục 3.3, 3.3b, danh sách, tìm kiếm).
~~~

In §3.4, replace:

```
Response `data` → `FollowResponse`: `{ "followerId": ..., "followingId": ... }`.

Lỗi: `CANNOT_FOLLOW_SELF` (400 — tự follow chính mình), `USER_BLOCKED` (403 — giữa 2 bên đang có quan hệ block, theo cả 2 chiều), `USER_PROFILE_NOT_FOUND` (404), `ALREADY_FOLLOWING` (409).
```

with:

```
Response `data` → `FollowResponse`: `{ "followerId": ..., "followingId": ..., "status": "FOLLOWING" }`.

`status` cho biết chuyện gì đã xảy ra — cả hai đều trả `201`:
- `FOLLOWING` — đã follow ngay (tài khoản công khai).
- `REQUESTED` — tài khoản **riêng tư**: server tạo **lời mời follow**, chưa follow. Chủ tài khoản duyệt thì mới thành follow (mục 3.16). Nút đổi thành "Requested"; bấm lại (`DELETE`, mục 3.5) là rút lời mời.

Lỗi: `CANNOT_FOLLOW_SELF` (400 — tự follow chính mình), `USER_BLOCKED` (403 — giữa 2 bên đang có quan hệ block, theo cả 2 chiều), `USER_PROFILE_NOT_FOUND` (404), `ALREADY_FOLLOWING` (409), `ALREADY_REQUESTED` (409 — đã có lời mời đang chờ duyệt).
```

In §3.5, replace:

```
Unfollow. Trả `200 OK`, `data` rỗng/null.

Lỗi: `NOT_FOLLOWING` (404), `CONCURRENT_MODIFICATION`
```

with:

```
Unfollow. Trả `200 OK`, `data` rỗng/null. Chưa follow nhưng đang có lời mời chờ duyệt (tài khoản riêng tư) thì endpoint này **rút lời mời** — cùng một nút trên UI, không phát thông báo gì.

Lỗi: `NOT_FOLLOWING` (404 — không follow, cũng không có lời mời nào đang chờ), `CONCURRENT_MODIFICATION`
```

In §3.6, replace:

```
Lỗi: `USER_PROFILE_NOT_FOUND` (404) — áp dụng cùng luật ẩn-do-block như mục 3.3 (không xem được followers của người đã block/bị block mình).
```

with:

```
Lỗi: `USER_PROFILE_NOT_FOUND` (404) — áp dụng cùng luật ẩn-do-block như mục 3.3 (không xem được followers của người đã block/bị block mình). `PRIVATE_ACCOUNT` (403) — tài khoản riêng tư mà người gọi không phải chủ, cũng không phải follower. Là 403 chứ không phải 404 vì profile đã công khai việc tài khoản riêng tư; UI hiện "This account is private".
```

In §3.14, replace:

~~~
  "weeklyScreenTimeDigest": true
}
```
~~~

with:

~~~
  "weeklyScreenTimeDigest": true,
  "privateAccount": false
}
```
~~~

and, still in §3.14, replace:

```
- `commentAudience` / `dmAudience` **có** hiệu lực thật, do interaction-service và chat-service enforce — xem mục 3.15.
```

with:

```
- `commentAudience` / `dmAudience` **có** hiệu lực thật, do interaction-service và chat-service enforce — xem mục 3.15.
- `privateAccount` được lưu trên **profile** chứ không phải bảng settings (mọi người xem đều cần nó — header profile, nút Follow), nhưng bật/tắt ở đây vì công tắc nằm trên trang `/setting`. Chưa có profile thì trả `false`.
```

In §3.15, replace:

~~~
  "weeklyScreenTimeDigest": false
}
```
~~~

with:

~~~
  "weeklyScreenTimeDigest": false,
  "privateAccount": true
}
```
~~~

and replace the table row:

```
| Key = `null`, với `commentAudience` / `dmAudience` / `weeklyScreenTimeDigest` | `VALIDATION_ERROR` (400) — ba cột này NOT NULL, không có `null` để xoá về |
```

with:

```
| Key = `null`, với `commentAudience` / `dmAudience` / `weeklyScreenTimeDigest` / `privateAccount` | `VALIDATION_ERROR` (400) — bốn cột này NOT NULL, không có `null` để xoá về |
```

- [ ] **Step 2: `docs/user-service-api.md` — the new sections, enums, errors and events**

Insert right before the `## 4. Enums` heading (after the last bullet of §3.15):

~~~
**`privateAccount`** — bật thì từ lúc đó chỉ người được duyệt mới follow được (mục 3.4, 3.16) và xem được video, danh sách followers/following, tab Reposts. **Follower hiện có giữ nguyên.** Gửi đúng giá trị đang có là no-op, không phát event. Tắt (về công khai) thì **mọi lời mời đang chờ được duyệt hết** trong cùng request — không còn gì chặn việc follow, và những người đó đã xin rồi. Chưa có profile → `USER_PROFILE_NOT_FOUND` (404). Video của tài khoản rời khỏi For You, trending, feed mới nhất và tìm kiếm trong vòng vài chục giây (video-service đánh dấu lại từng video, 100 video mỗi 5 giây).

### 3.16 Lời mời follow — `GET /me/follow-requests`, `POST /me/follow-requests/{requesterId}/approve`, `DELETE /me/follow-requests/{requesterId}`

Phía **chủ tài khoản riêng tư**. **Bắt buộc token.**

| Endpoint | Trả về | Lỗi |
|---|---|---|
| `GET /me/follow-requests` | `200`, `data` → `Page<UserProfileResponse>` của người xin follow, **mới nhất trước**. Phân trang giống mục 3.6 | — |
| `POST /me/follow-requests/{requesterId}/approve` | `200`, `data` → `FollowResponse` với `status: "FOLLOWING"` | `FOLLOW_REQUEST_NOT_FOUND` (404) |
| `DELETE /me/follow-requests/{requesterId}` | `200`, `data` null. **Im lặng** — người xin không được báo | `FOLLOW_REQUEST_NOT_FOUND` (404) |

- Duyệt tạo follow y như follow thường (counter, event). Người xin nhận thông báo `FOLLOW_REQUEST_ACCEPTED`; chủ tài khoản **không** nhận `NEW_FOLLOWER` (chính họ vừa bấm duyệt).
- Có lời mời mới thì chủ tài khoản nhận thông báo `FOLLOW_REQUEST`. Lời mời bị rút hoặc bị từ chối **vẫn còn thông báo trong inbox** — danh sách ở đây mới là nguồn sự thật; bấm vào thông báo cũ thì mở danh sách này.
- Block (bất kể chiều nào) xoá luôn lời mời giữa hai bên.
- Bị từ chối rồi vẫn xin lại được — đó là một lời mời mới.

### 3.17 `GET /{userId}/friendship`

Quan hệ giữa người gọi và `userId` — đủ cho nút Follow trong **một** lần gọi. **Bắt buộc token.** Trả `200 OK`, `data` → `FriendshipResponse`:

```json
{
  "userId": 42,
  "friends": false,
  "following": false,
  "requested": true,
  "privateAccount": true
}
```

- `following` — mình đang follow người này. `requested` — mình có lời mời đang chờ duyệt. `friends` — follow **lẫn nhau**. `privateAccount` — tài khoản kia riêng tư.
- Nút Follow: `following` → "Following"; `requested` → "Requested"; còn lại → "Follow". Không cần tự dò danh sách following của mình nữa.
- Không có lỗi `404`: id không tồn tại chỉ trả toàn `false`.

~~~

In §4, replace:

```
| `SettingsAudience` | `EVERYONE`, `FRIENDS`, `NO_ONE` | `commentAudience` và `dmAudience` ở mục 3.14 / 3.15. `FRIENDS` nghĩa là **follow lẫn nhau** (mutual), không phải "người tôi follow" |

Ngoài ra không có enum nào khác ở user-service (không có "visibility", "follow status"... — follow/block/mute chỉ là quan hệ có/không, xoá mềm qua `deletedAt`). Enum `UserRole`/`UserStatus` thuộc về `auth-service`, không lặp lại ở đây.
```

with:

```
| `SettingsAudience` | `EVERYONE`, `FRIENDS`, `NO_ONE` | `commentAudience` và `dmAudience` ở mục 3.14 / 3.15. `FRIENDS` nghĩa là **follow lẫn nhau** (mutual), không phải "người tôi follow" |
| `FollowResponse.status` | `FOLLOWING`, `REQUESTED` | `status` ở mục 3.4 và 3.16 |

Ngoài ra không có enum nào khác ở user-service — follow/block/mute và lời mời follow đều là quan hệ có/không, xoá mềm qua `deletedAt`. Enum `UserRole`/`UserStatus` thuộc về `auth-service`, không lặp lại ở đây.
```

In §5, add after the `USER_BLOCKED` row:

```
| `PRIVATE_ACCOUNT` | 403 | Xem danh sách followers/following của tài khoản riêng tư mà không phải chủ hay follower (mục 3.6) |
```

add after the `ALREADY_FOLLOWING` row:

```
| `ALREADY_REQUESTED` | 409 | Follow tài khoản riêng tư khi đã có lời mời đang chờ (mục 3.4) |
```

and replace the `NOT_FOLLOWING` row:

```
| `NOT_FOLLOWING` | 404 | Unfollow người chưa follow |
```

with:

```
| `NOT_FOLLOWING` | 404 | Unfollow người chưa follow và cũng không có lời mời đang chờ |
| `FOLLOW_REQUEST_NOT_FOUND` | 404 | Duyệt/từ chối khi không có lời mời nào đang chờ từ người đó (mục 3.16) |
```

In §8, replace:

```
Phần này cho người tích hợp backend, không phải client mobile. `user-service` **chỉ tiêu thụ**, không phát event nào (follow/block/mute chỉ ghi Postgres, không announce ra Kafka). Bảng đầy đủ toàn hệ thống: `docs/ARCHITECTURE.md` §5c.
```

with:

```
Phần này cho người tích hợp backend, không phải client mobile. `user-service` vừa phát vừa tiêu thụ event. Bảng đầy đủ toàn hệ thống: `docs/ARCHITECTURE.md` §5c.

### Phát (producer) — outbox

Ghi vào bảng `outbox_events` trong **cùng transaction** với thay đổi; `OutboxPublisher` poll mỗi 5s và chỉ đánh dấu published sau khi broker ack. Mỗi `aggregate_type` một topic, mỗi topic đúng một loại event — không có header `eventType`.

| Topic | Event | Key | Payload | Khi nào |
|---|---|---|---|---|
| `user.follow-events` | `UserFollowChangedEvent` | `followingId` | `eventId, occurredAt, followerId, followingId, followed, requestApproved` | Follow / unfollow / block cắt follow; duyệt lời mời (`requestApproved = true`). Event cũ không có `requestApproved` → đọc là `false` |
| `user.follow-request-events` | `FollowRequestedEvent` | `targetId` | `eventId, occurredAt, requesterId, targetId` | Follow tài khoản riêng tư. Rút hay từ chối **không** phát gì |
| `user.privacy-events` | `UserPrivacyChangedEvent` | `userId` | `eventId, occurredAt, userId, privateAccount` | `privateAccount` ở mục 3.15 thật sự đổi giá trị |

Consumer: `user.follow-events` → notification-service (`NEW_FOLLOWER` / `FOLLOW_REQUEST_ACCEPTED`) và chat-service (counter realtime); `user.follow-request-events` → notification-service (`FOLLOW_REQUEST`); `user.privacy-events` → video-service (collection `owner_privacy` và đánh dấu lại video).

### Tiêu thụ (consumer)
```

Replace the last line of §8:

```
`kafka-lib` error handler: `user-service` **có** dùng → consumer lỗi retry 3 lần → `<topic>.DLT`. Không có outbox (không phát event).
```

with:

```
`kafka-lib` error handler: `user-service` **có** dùng → consumer lỗi retry 3 lần → `<topic>.DLT`.
```

- [ ] **Step 3: `docs/video-service-api.md`**

In the `VideoResponse` example of §3.2, replace:

```
  "accountCommentsOff": false,    // tài khoản chủ video đặt "Ai được bình luận" = No one → mọi video của họ coi như tắt comment, kể cả với chính chủ
```

with:

```
  "accountCommentsOff": false,    // tài khoản chủ video đặt "Ai được bình luận" = No one → mọi video của họ coi như tắt comment, kể cả với chính chủ
  "ownerPrivate": false,          // tài khoản chủ video đang riêng tư → video PUBLIC chỉ follower xem được; hiện ổ khoá. `visibility` vẫn là lựa chọn của chủ video
```

In §3.3, replace:

```
Chỉ trả video **`PUBLISHED` + `PUBLIC`** và chưa bị xoá. Video của chính mình đang `PROCESSING`/`PRIVATE` **không** xuất hiện ở đây kể cả khi có gửi token — muốn xem video của mình thì dùng mục 3.5.
```

with:

```
Chỉ trả video **`PUBLISHED` + `PUBLIC`**, chưa bị xoá, của tài khoản **không riêng tư**. Video của chính mình đang `PROCESSING`/`PRIVATE` **không** xuất hiện ở đây kể cả khi có gửi token — muốn xem video của mình thì dùng mục 3.5. Video của tài khoản riêng tư không bao giờ lên feed này, kể cả với follower — follower thấy chúng ở tab Following (mục 3.3b).
```

In §3.3b, replace:

```
Bộ lọc hiển thị **y hệt mục 3.3**: chỉ `PUBLISHED` + `PUBLIC` và chưa xoá. Follow một người không cho bạn thấy video `PRIVATE`, video đang `PROCESSING`, hay video bị `TAKEN_DOWN` của họ. Vì thế endpoint không cần token và việc khai một `userId` mình không thực sự follow cũng không lộ thêm gì so với `GET /users/{userId}` ở mục 3.5.
```

with:

```
Bộ lọc hiển thị giống mục 3.3: chỉ `PUBLISHED` + `PUBLIC` và chưa xoá. Follow một người không cho bạn thấy video `PRIVATE`, video đang `PROCESSING`, hay video bị `TAKEN_DOWN` của họ. Khác mục 3.3 ở một chỗ: video của tài khoản **riêng tư** có xuất hiện ở đây — nhưng chỉ với follower của họ. Vì danh sách id do client tự khai, server kiểm tra lại: tài khoản riêng tư trong `followedUserIds` mà người gọi không follow bị **bỏ khỏi danh sách trước khi truy vấn** (trang không bị hụt, cursor không lệch). **Gửi token nếu đã đăng nhập** — không có token thì mọi tài khoản riêng tư đều bị bỏ.
```

In §3.4, replace:

```
- **Người khác / không có token**: chỉ xem được video `PUBLISHED` + `PUBLIC`.
```

with:

```
- **Người khác / không có token**: chỉ xem được video `PUBLISHED` + `PUBLIC`. Nếu chủ video để tài khoản **riêng tư** (`ownerPrivate = true`) thì video `PUBLIC` chỉ follower của họ xem được; người khác nhận `VIDEO_NOT_FOUND` như với video `PRIVATE`. Video `FRIENDS` vẫn theo luật bạn bè (follow lẫn nhau).
```

In §3.5, replace:

```
- Gọi user khác (hoặc không token): chỉ `PUBLISHED` + `PUBLIC`.
```

with:

```
- Gọi user khác (hoặc không token): chỉ `PUBLISHED` + `PUBLIC`.
- Tài khoản **riêng tư**, người gọi không phải follower (hoặc không token): `PRIVATE_ACCOUNT` (403) — UI hiện "This account is private" thay vì lưới trống. Follower xem lưới như bình thường. `GET /users/{userId}/stats` vẫn công khai (các con số trên header profile).
```

In §6, add after the `NOT_VIDEO_OWNER` row:

```
| `PRIVATE_ACCOUNT` | 403 | Lưới video (mục 3.5) của tài khoản riêng tư mà người gọi không follow |
```

In §8, in the producer table, replace the `VideoPublishedEvent` payload cell `` `eventId, occurredAt, videoId, userId, title, description, rawFileUrl, tags[]` `` with:

```
`eventId, occurredAt, videoId, userId, title, description, rawFileUrl, visibility, tags[]`
```

After the paragraph that starts ``description` và `tags` được mang trên `VideoPublishedEvent``, add:

```
`visibility` trên `VideoPublishedEvent` và `VideoVisibilityChangedEvent` là **audience thực tế**, không phải giá trị lưu: video `PUBLIC` của tài khoản riêng tư đi ra là `FOLLOWERS`. Mọi consumer giữ bản sao (search, recommendation, chat) coi mọi thứ khác `PUBLIC` là ẩn, nên video đó rời khỏi tìm kiếm, For You và trending mà các service kia không phải sửa gì. `VideoResponse.visibility` vẫn là giá trị lưu (`PUBLIC`/`FRIENDS`/`PRIVATE`) để chủ video thấy đúng lựa chọn của mình. chat-service chuyển nguyên chữ này xuống trình duyệt trong frame `state` — client phải hiểu `FOLLOWERS` là "không công khai" và không ghi nó vào công tắc visibility của chủ video.

`VideoVisibilityChangedEvent` chỉ được gửi sau khi `VideoPublishedEvent` của chính video đó đã được ack: hai poll chạy trên hai thread riêng, và không có điều kiện này thì event visibility có thể tới partition trước chính video.
```

In §8, add after the `admin.moderation-events` row of the consumer table:

```
| `user.privacy-events` | `UserPrivacyChangedEvent` | Ghi `owner_privacy` (`_id = userId`) nếu event không cũ hơn bản đang có; đánh dấu `ownerPrivate` trên mọi video chưa xoá của user, xếp hàng `VideoVisibilityChangedEvent` cho từng video, xoá cache. Event cũ tới sau (replay từ DLT) không làm gì |
```

- [ ] **Step 4: `docs/interaction-service-api.md`**

Insert right before the `## 4. Counter đến từ hai nơi — đọc số nào, khi nào` heading:

~~~
### 3.11 `GET /users/{userId}/reposts` — tab Reposts của một profile
Nằm ngoài `/videos/...`. Không bắt buộc token, **nhưng gửi nếu đã đăng nhập**. Query param: `?cursor=<nextCursor>&size=20` (tối đa 50). Trả `200 OK`, `data` → `VideoIdPageResponse`:

```json
{
  "videoIds": [7312458901234567],
  "nextCursor": "…",
  "hasMore": true
}
```

- Tài khoản công khai: ai cũng xem được, giống chính chủ.
- Tài khoản **riêng tư**: chỉ chủ và follower. Người khác (kể cả không token) → `PRIVATE_ACCOUNT` (403). Token là cách server nhận ra follower, nên thiếu token là bị coi như người lạ.
- Mỗi lần mở tab, service hỏi user-service; user-service không trả lời được thì coi như riêng tư và không follow (fail-closed) → 403.

~~~

In §5, add after the `COMMENTS_NOT_ALLOWED` row:

```
| `PRIVATE_ACCOUNT` | 403 | Tab Reposts (mục 3.11) của tài khoản riêng tư mà người gọi không phải chủ hay follower |
```

- [ ] **Step 5: `docs/user-settings.md` §0, `docs/ARCHITECTURE.md` §5c and the spec's status**

In `docs/user-settings.md`, replace:

```
- **Private account** — not a boolean. It needs follow requests (`user_follow_requests`, an
  approve/reject flow, `POST /users/{id}/follow` returning `202 PENDING`) and an enforcement pass
  across five services. Critically, `VideoServiceImpl.isVisibleTo` reads only `VideoVisibility`, so
  a private account's PUBLIC videos stay in the global feed; fixing that means denormalising
  `ownerPrivate` onto the video document, because the feed query cannot call user-service per row.
  Own document, own sprint.
```

with:

```
- **Private account** — built on its own, see
  `docs/superpowers/specs/2026-09-26-private-account-design.md`. The switch is written through
  `PATCH /me/settings` like the rest, but the flag lives on `user_profiles` (V11) because every
  viewer reads it; follow requests are `user_follow_requests` (V12).
```

In `docs/ARCHITECTURE.md` §5c, add after the `media.avatar-events` row:

```
| `user.follow-events` | user-service (outbox) | — (1 shape) | `UserFollowChangedEvent` (+ `requestApproved` khi là lời mời vừa được duyệt) | notification-service, chat-service |
| `user.follow-request-events` | user-service (outbox) | — (1 shape) | `FollowRequestedEvent` | notification-service |
| `user.privacy-events` | user-service (outbox) | — (1 shape) | `UserPrivacyChangedEvent` | video-service |
```

In `docs/superpowers/specs/2026-09-26-private-account-design.md`, replace `Status: draft for review` with `Status: approved`.

- [ ] **Step 6: Check nothing stale is left**

Run:
```bash
grep -n "chỉ tiêu thụ\|Không có outbox" docs/user-service-api.md
grep -c "PRIVATE_ACCOUNT" docs/user-service-api.md docs/video-service-api.md docs/interaction-service-api.md
grep -n "Own document, own sprint" docs/user-settings.md
grep -n "user.privacy-events" docs/ARCHITECTURE.md
```
Expected:
- The first and third commands print nothing.
- The second prints a count of at least 1 for each of the three files.
- The fourth prints one row.

- [ ] **Step 7: Commit**

```bash
git add docs/user-service-api.md docs/video-service-api.md docs/interaction-service-api.md \
        docs/user-settings.md docs/ARCHITECTURE.md docs/superpowers/specs/2026-09-26-private-account-design.md
git commit -m "$(cat <<'EOF'
docs: document private accounts across the API contracts

user-service: privateAccount on profiles and settings, follow requests, the
relationship answer, PRIVATE_ACCOUNT on lists, and the events it now
publishes (the old "consumes only" note was stale). video-service: the
private owner rules per endpoint and the FOLLOWERS audience on its events.
interaction-service: the Reposts tab. Adds the user.* topics to the
architecture table and marks the spec approved.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

## Final check

- [ ] **Run the four touched services together, from a clean reactor**

Run: `./mvnw test -pl services/user-service,services/video-service,services/interaction-service,services/notification-service -am`
Expected: BUILD SUCCESS.

- [ ] **Check the other services that read the changed events still compile**

Run: `./mvnw -q test-compile -pl services/chat-service,services/search-service,services/recommendation-service -am`
Expected: BUILD SUCCESS.

- [ ] **Confirm the branch holds the plan and twelve task commits, and nothing is pushed**

Run: `git log --oneline dfb94ad..HEAD && git status --short`
Expected: 13 commits (this plan's commit plus one per task), and a clean tree.

Out of scope here, next: the frontend.
- The settings switch.
- The Requested state on the Follow button.
- The private profile state for a 403.
- The follow requests list.
- The two new notification types.
- Treating `FOLLOWERS` in a `state` frame as "not public".
