# Story Rings and Story Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put a ring on every avatar whose owner has a story the viewer may watch, and tell the followers allowed to watch a new story with one collapsed "posted a new story" notification.

**Architecture:** story-service answers ring state for up to 100 accounts per call (`GET /stories/rings`) with the audience rules its reads already use; the web app batches every avatar on screen into those calls through one provider, which also hosts the story viewer the rings open. When moderation makes a story ACTIVE, story-service stamps `activatedAt` and a second outbox poll publishes `StoryActivatedEvent` on `story.activation-events`. notification-service re-checks the story over an internal story-service endpoint, pages the audience from a new internal user-service endpoint, and writes one collapsing STORY entry per recipient.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data MongoDB and JPA (PostgreSQL), Spring Kafka + `kafka-lib`, JUnit 5 + Mockito + Testcontainers; Next.js 16 / React 19 / TypeScript strict / Tailwind v4 / Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-10-22-story-rings-and-notifications-design.md`. Read it first; this plan argues from it.

## Global Constraints

- Branches: backend on `feat/stories`, web (`../tiktok-cloned`) on `feat/stories`. Run `git branch --show-current` before every commit; the session-start status has named the wrong branch here before.
- **Uncommitted work that is not this plan's.** Both repos carry it. Never `git add -A` / `git add .`, never stash, checkout or revert it.
  - Backend files this plan touches that already hold such edits (2026-10-22): `CLAUDE.md`, `docker-compose.prod.yml`, `services/story-service/src/main/java/com/tiktok/storyservice/service/StoryServiceImpl.java`. Commit only this task's change to them: `git show HEAD:<file> > "$TMP/f"`, make the task's edit to `"$TMP/f"` as well, `git update-index --cacheinfo 100644,$(git hash-object -w "$TMP/f"),<file>`, then `git diff --cached <file>` must show this task's lines and nothing else.
  - Web: Tasks 8–9 build on the uncommitted story and following-sidebar work (spec §9) — `ProfileStoryAvatar.tsx`, `StoryViewer.tsx`, `StoryComposer.tsx`, `StoryOwnerPanel.tsx`, and the untracked `side-nav/FollowingAccounts.tsx`. Before Task 8, if `git -C ../tiktok-cloned status --short` still lists them, stop and ask the user to commit that work first.
- Rings (spec §5.1): another owner is present iff they have an ACTIVE, unexpired, undeleted story whose visibility `StoryAudience.resolve` allows the requester; `hasUnseen` iff one of those has no `story_views` row for the requester (always true for a guest). The requester's own id is present iff they have any unexpired, undeleted story; `hasUnseen` iff one is ACTIVE. More than 100 ids → 400. No media signing, no new index.
- Ring colours: `unseen` = `--tt-red`, `seen` = `bg-white/30` (the existing `StoryRing`).
- Topic `story.activation-events`, key = owner id as a string, value `StoryActivatedEvent(eventId, occurredAt, storyId, userId)` in `com.tiktok.event.story`. Never on `story.story-events`.
- Activation poll: `@Scheduled(fixedDelay = 5000)`, batch 100, oldest `activatedAt` first, through `OutboxDispatcher`. Filter: `status = ACTIVE`, `activatedAt != null`, `announcedAt == null`, `deletedAt == null`, `expiresAt > now`, `visibility in (PUBLIC, FRIENDS)`.
- Internal endpoints: `GET /api/v1/stories/internal/{storyId}/announcement` → `{userId, visibility}` or 404; `GET /api/v1/users/internal/story-audience?userId=&scope=FOLLOWERS|FRIENDS&after=&size=` → `{userIds, next}`, `size` ≤ 500. Each guarded with `.access(new InternalCallerAuthorization(internalApiToken))` in its own service; `/api/v1/stories/internal/**` denied at the gateway.
- Notification: `NotificationType.STORY`, collapsing; `create(recipient, owner, STORY, "New story", "Someone posted a new story.", String.valueOf(owner))`. Config `downstream.story-service-uri: ${STORY_SERVICE_URI:http://localhost:8086}`.
- Web copy: `describe()` → `"posted a new story"`. No new filter chip.
- Repo rules: constructor injection, records for DTOs, no `@Data` on documents or entities, `@MockitoBean` (not `@MockBean`), Jackson 3 (`tools.jackson.*`), every consumer idempotent. Web: `AGENTS.md` + `FRONTEND_RULES.md` — calls through `apiFetch`, pure logic in `src/lib/` with a Vitest test, Tailwind only, comments only for a non-obvious why; `npm run check` and `npm test` before done.
- Docs are written in the language of the file they go in (the API docs and `CLAUDE.md` are Vietnamese).

## Review Focus

1. **A ringed avatar inside a link or a clickable row** (Following list row, follower modal, suggested-creator card, Activity row): a click or Enter opens the stories and does nothing else — no navigation, no row action. Test: Task 8, `StoryAvatar.test.tsx`.
2. **The viewer signs out or switches account** with rings cached: the next viewer never sees the previous viewer's rings (FRIENDS stories, private accounts). Test: Task 8, `StoriesProvider.test.tsx`.
3. **A stale ring** (the story expired or was deleted, or the owner blocked the viewer after the ring loaded): clicking it lands on the profile and the ring goes away. Test: Task 8.
4. **More than 100 avatars on one screen** (long comment thread, explore grid after scrolling): ids go out in chunks of ≤ 100; otherwise the server answers 400 and every ring on the page disappears. Test: Task 7.
5. **The fan-out fails halfway** (user-service drops between pages): the event is retried, and followers already notified get nothing twice. Tests: Task 6.

---

### Task 1: `StoryActivatedEvent`

**Files:**
- Create: `libs/event-schema/src/main/java/com/tiktok/event/story/StoryActivatedEvent.java`
- Modify: `libs/event-schema/src/test/resources/event-json.json` (recorded, never hand-written)

**Interfaces:**
- Produces: `record StoryActivatedEvent(String eventId, Instant occurredAt, String storyId, Long userId) implements DomainEvent` + `static StoryActivatedEvent of(String storyId, Long userId)` (random UUID, `Instant.now()`). Javadoc: published by story-service once a story is ACTIVE with a visibility followers can see; consumed by notification-service; carries no visibility on purpose — the consumer reads the current one (spec §6.3).

- [ ] **Step 1:** Add the record, shaped like `StoryCreatedEvent`.
- [ ] **Step 2:** `./mvnw test -pl libs/event-schema` → FAIL: `EventJsonCompatibilityTest` reports no pinned JSON for `StoryActivatedEvent`.
- [ ] **Step 3:** `./mvnw test -pl libs/event-schema -Devent-json.record=true`, then `./mvnw test -pl libs/event-schema` → PASS. `git diff libs/event-schema/src/test/resources/event-json.json` shows one new entry and no other change.
- [ ] **Step 4:** `./mvnw install -pl libs/event-schema -DskipTests` (story- and notification-service compile against it).
- [ ] **Step 5:** Commit `feat(event-schema): add StoryActivatedEvent`.

### Task 2: story-service stamps activation and publishes it

**Files:**
- Modify: `services/story-service/src/main/java/com/tiktok/storyservice/entity/Story.java`
- Modify: `.../repository/StoryRepositoryCustom.java`, `.../repository/StoryRepositoryImpl.java`
- Modify: `.../event/producer/StoryEventPublisher.java`, `.../config/OutboxMetricsConfig.java`
- Modify: `docs/story-service-api.md` §1
- Create: `services/story-service/src/test/java/com/tiktok/storyservice/repository/StoryRepositoryImplTest.java`
- Modify: `services/story-service/src/test/java/com/tiktok/storyservice/event/producer/StoryEventPublisherTest.java`

**Interfaces:**
- Consumes: `StoryActivatedEvent.of(storyId, userId)` (Task 1).
- Produces:
  - `Story.activatedAt` (`Instant`; when moderation made it ACTIVE — null before, and on stories that went live before the field existed) and `Story.announcedAt` (`Instant`; the outbox flag for `StoryActivatedEvent` — `eventPublishedAt` stays the created event's).
  - `StoryRepositoryCustom.decide(...)` keeps its signature; when `to == ACTIVE` it also sets `activatedAt` to now.
  - `List<Story> StoryRepositoryCustom.findAnnounceable(Instant now, int limit)` — the poll filter (Global Constraints), `activatedAt` ascending.
  - `void StoryRepositoryCustom.markAnnounced(String storyId, Instant at)`.
  - `public static Criteria StoryRepositoryImpl.announceable(Instant now)` — that filter, shared with the gauge so the two cannot drift.
  - `StoryEventPublisher.ACTIVATION_TOPIC = "story.activation-events"`; `@Scheduled(fixedDelay = 5000) public void publishActivated()`.
  - `OutboxBacklog OutboxMetricsConfig.storyActivatedOutboxBacklog(MongoTemplate)` named `"story-activated"`: count of `announceable(now)`, oldest `activatedAt`.

- [ ] **Step 1:** Write `StoryRepositoryImplTest` against a real Mongo, set up like `OwnerStateConsumersTest` (`@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")`, `@Testcontainers`, `@ActiveProfiles("test")`, `MongoDBContainer("mongo:7")` with `@ServiceConnection`, `@MockitoBean KafkaTemplate<String, String>`, `deleteAll()` in `@BeforeEach`; every story needs its own `mediaKey`, the field is unique):
  - `decideToActiveStampsActivatedAt` — insert `Story.pending(...)`; `decide(id, ACTIVE, now + 24h, null, moderation)` → reloaded `activatedAt` within 5s of now.
  - `decideToHeldOrRejectedLeavesActivatedAtNull`.
  - `announceableSkipsWhatMustNotBeAnnounced` — insert, ACTIVE unless stated: `a` PUBLIC activated 2 min ago; `b` FRIENDS activated 1 min ago; `c` PUBLIC without `activatedAt` (live before the deploy); `d` PRIVATE activated; `e` activated, `deletedAt` set; `f` activated, `expiresAt` 1 min ago; `g` activated, `announcedAt` set; `h` HELD with `activatedAt` set → `findAnnounceable(now, 100)` ids are exactly `["a", "b"]`, in that order.
  - `markAnnouncedTakesAStoryOutOfThePoll` — after `markAnnounced("a", now)` the poll returns `["b"]`.
- [ ] **Step 2:** Add to `StoryEventPublisherTest`:
  - `publishesActivationsKeyedByOwnerAndMarksOnlyAcknowledged` — `findAnnounceable(any(), eq(100))` returns ACTIVE stories `"1"` and `"2"` of owner 7; the first send acks, the second fails → the first record has topic `story.activation-events`, key `"7"`, and a value that parses to `StoryActivatedEvent` with `storyId "1"`, `userId 7`; `markAnnounced("1", any)` once, never for `"2"`.
  - `noActivationsTouchesNoBroker`.
- [ ] **Step 3:** `./mvnw test -pl services/story-service -Dtest='StoryRepositoryImplTest,StoryEventPublisherTest'` → FAIL (does not compile).
- [ ] **Step 4:** Implement. `publishActivated` mirrors `publishPending`, including the warning when fewer were published than polled. `findAnnounceable` carries a `ponytail:` note: a collection scan per poll, fine while the collection holds at most 48h of stories; a partial index on `announcedAt` is the upgrade.
- [ ] **Step 5:** `./mvnw test -pl services/story-service` → PASS (`StoryModerationEventConsumerTest` is untouched: `decide` kept its signature).
- [ ] **Step 6:** `docs/story-service-api.md` §1: add `ACTIVE + PUBLIC/FRIENDS → story.activation-events (StoryActivatedEvent, key userId) → notification-service` to the flow block, and one sentence: a story is announced once — when it becomes ACTIVE, or, if posted PRIVATE, when widened to PUBLIC/FRIENDS within its 24h; stories already live before this shipped are never announced.
- [ ] **Step 7:** Commit `feat(story-service): announce stories once they go live`.

### Task 3: story-service `GET /stories/rings`

**Files:**
- Create: `services/story-service/src/main/java/com/tiktok/storyservice/dto/response/StoryRingResponse.java`, `.../exception/TooManyRingUsersException.java`
- Modify: `.../service/StoryService.java`, `.../service/StoryServiceImpl.java` (holds someone else's edit — stage with the recipe), `.../controller/StoryController.java`, `.../config/SecurityConfig.java`
- Modify: `services/api-gateway/src/main/java/com/tiktok/apigateway/config/SecurityConfig.java`
- Modify: `docs/story-service-api.md` intro, §4, §5
- Test: `StoryServiceImplTest`, `StoryControllerTest`, `services/api-gateway/src/test/java/com/tiktok/apigateway/config/GatewaySecurityRulesTest.java`

**Interfaces:**
- Produces:
  - `record StoryRingResponse(Long userId, boolean hasUnseen)`.
  - `TooManyRingUsersException(int max) extends BadRequestException`, code `TOO_MANY_RING_USERS` (shaped like `TooManyFollowedUsersException`).
  - `List<StoryRingResponse> StoryService.rings(Long requesterId, List<Long> userIds)`; `StoryServiceImpl.MAX_RING_USERS = 100`. One entry per id that gets a ring, in request order.
  - `GET /api/v1/stories/rings?userIds=1,2` → `ApiResponse<List<StoryRingResponse>>`, token optional.

- [ ] **Step 1:** Tests in `StoryServiceImplTest`, using its `story(...)` / `active(...)` helpers:
  - `ringsMarkOwnRedOnlyWhileAStoryIsActive` — own stories `[PENDING_MODERATION]` → `[{100, false}]`; `[HELD, ACTIVE]` → `[{100, true}]`; none → `[]`.
  - `ringsOfOthersFollowWhatTheViewerHasSeen` — owner 7 has ACTIVE `s1`, `s2`, audience allows PUBLIC; viewer 100 saw `s1` → `{7, true}`; saw both → `{7, false}`.
  - `ringsLeaveOutOwnersTheViewerMayNotSee` — `storyAudience.resolve` gives owner 8 an empty set → 8 absent; `verifyNoInteractions(storyMedia)`.
  - `ringsForAGuestAreAllUnseen` — `rings(null, [7])` → `[{7, true}]`; `storyViewRepository` never asked.
  - `ringsRejectMoreThan100Ids` — 101 ids → `TooManyRingUsersException`; `storyRepository` untouched.
  - `ringsOfNobodyAskNothing` — null and empty lists → `[]`; `verifyNoInteractions(storyRepository)`.
- [ ] **Step 2:** `StoryControllerTest`: `anonymousMayReadRings` — `get("/api/v1/stories/rings").param("userIds", "1", "2")` → 200, `verify(storyService).rings(null, List.of(1L, 2L))`; `signedInViewerReadsRingsAsThemselves` — with `bearer(9L)` → `rings(9L, ...)`.
- [ ] **Step 3:** `GatewaySecurityRulesTest`: add `/api/v1/stories/rings?userIds=1` to the pass-without-token list in `storyReads_passWithoutAToken_butTheFeedAndViewersDoNot`, and `"/api/v1/stories/rings"` to `Stub`.
- [ ] **Step 4:** `./mvnw test -pl services/story-service,services/api-gateway` → FAIL.
- [ ] **Step 5:** Implement `rings` in `StoryServiceImpl` beside `feed`, reusing its private `visibleOf` and `seenAmong`: cap check on the raw list (as user-service's `followingAmong`), then distinct non-null ids; the own id through `findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc` only when it is in the list; the rest through `findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(..., ACTIVE, now)`. Never call `storyMedia`. Security, story-service and gateway alike: name `/api/v1/stories/rings` in the GET permitAll rule right after the `/feed` rule.
- [ ] **Step 6:** `./mvnw test -pl services/story-service,services/api-gateway` → PASS.
- [ ] **Step 7:** Docs: §4 row `GET /rings`, no token, `userIds` (≤ 100) → `[{userId, hasUnseen}]`, only ids with a ring, plus the own-id rule; §5 row `TOO_MANY_RING_USERS | 400`; the intro's token rule lists `/rings` with the reads that need no token.
- [ ] **Step 8:** Commit `feat(story-service): ring state for many avatars in one call`.

### Task 4: story-service internal announcement check

**Files:**
- Create: `services/story-service/src/main/java/com/tiktok/storyservice/controller/InternalStoryController.java`, `.../dto/response/StoryAnnouncementResponse.java`
- Modify: `.../service/StoryService.java`, `.../service/StoryServiceImpl.java` (recipe), `.../service/StoryAudience.java`, `.../config/SecurityConfig.java`
- Modify: api-gateway `SecurityConfig.java` (`denyAll` list)
- Modify: `docs/story-service-api.md` (new section "Endpoint nội bộ"), `CLAUDE.md` §Endpoint nội bộ (recipe)
- Test: `StoryServiceImplTest`, `StoryControllerTest`, `GatewaySecurityRulesTest`

**Interfaces:**
- Produces:
  - `record StoryAnnouncementResponse(Long userId, StoryVisibility visibility)`.
  - `boolean StoryAudience.isHidden(Long ownerId)` — banned or closed, from `ownerBanRepository.findHiddenAmong(List.of(ownerId), Instant.now())`.
  - `StoryAnnouncementResponse StoryService.announcement(String storyId)` — throws `StoryNotFoundException` unless the story is undeleted, unexpired, ACTIVE, and its owner is not hidden.
  - `GET /api/v1/stories/internal/{storyId}/announcement` on `InternalStoryController` → `ApiResponse<StoryAnnouncementResponse>`.

- [ ] **Step 1:** Tests:
  - `StoryServiceImplTest.announcementOfALiveStoryNamesOwnerAndVisibility` → `{7, FRIENDS}`.
  - `StoryServiceImplTest.announcementOfAStoryThatCannotBeAnnouncedIsNotFound` — repository empty (deleted or unknown), expired, HELD, owner hidden (`storyAudience.isHidden(7)` true) → `StoryNotFoundException` for each.
  - `StoryControllerTest.internalAnnouncementAnswersOnlyTheInternalToken` — no token 403, a user's JWT 403, `X-Internal-Token` 200. The class now uses `@WebMvcTest(controllers = {StoryController.class, InternalStoryController.class})`, adds `InternalApiAutoConfiguration.class` to `@Import` and `INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef` to `@TestPropertySource`, and autowires `InternalApiToken` — as `VideoControllerTest` does.
  - `GatewaySecurityRulesTest.internalStoryEndpoints_areRefusedWithAndWithoutAToken` — `/api/v1/stories/internal/s1/announcement` → 403 with a token, 401 without; add the path to `Stub`.
- [ ] **Step 2:** `./mvnw test -pl services/story-service,services/api-gateway` → FAIL.
- [ ] **Step 3:** Implement. story-service `SecurityConfig` injects `InternalApiToken` and matches `GET /api/v1/stories/internal/**` with `.access(new InternalCallerAuthorization(internalApiToken))` before every other stories rule. The gateway adds `"/api/v1/stories/internal/**"` to its `denyAll` list.
- [ ] **Step 4:** `./mvnw test -pl services/story-service,services/api-gateway` → PASS.
- [ ] **Step 5:** Docs: story-service-api.md "Endpoint nội bộ" — path, response, the 404 cases, internal token, denied at the gateway, caller notification-service. `CLAUDE.md` §Endpoint nội bộ: add `/api/v1/stories/internal/**` to the list of internal prefixes.
- [ ] **Step 6:** Commit `feat(story-service): internal announcement check for notification-service`.

### Task 5: user-service internal story audience

**Files:**
- Create: `services/user-service/src/main/java/com/tiktok/userservice/dto/request/StoryAudienceScope.java`, `.../dto/response/StoryAudienceResponse.java`
- Modify: `.../repository/UserFollowRepository.java`, `.../service/FollowService.java`, `.../service/FollowServiceImpl.java`, `.../controller/InternalFollowController.java`
- Modify: `docs/user-service-api.md` §3.11
- Create: `services/user-service/src/test/java/com/tiktok/userservice/repository/StoryAudienceQueryTest.java`

**Interfaces:**
- Produces:
  - `enum StoryAudienceScope { FOLLOWERS, FRIENDS }`.
  - `record StoryAudienceResponse(List<Long> userIds, Long next)` — `next` is the last id when the page is full, else null.
  - `List<Long> UserFollowRepository.findStoryAudience(Long ownerId, boolean friendsOnly, long after, Pageable limit)` — JPQL over live `UserFollow` rows into `ownerId` with `followerId > after`, ordered by `followerId`; the block and deactivated-profile filters of `findFollowerIdsVisibleTo` with `ownerId` as the viewer; `not exists` a live `UserMute` with `muterId = f.followerId and mutedId = ownerId` (starts from the muter, matching the only index `user_mutes` has — see `UserMuteRepository`); when `friendsOnly`, `exists` a live `UserFollow` from `ownerId` to `f.followerId`.
  - `StoryAudienceResponse FollowService.storyAudience(Long ownerId, StoryAudienceScope scope, Long after, Integer size)` — `size` null → 500, clamped to 1..500; `after` null → 0.
  - `GET /api/v1/users/internal/story-audience` on `InternalFollowController` (`userId`, `scope` required; `after`, `size` optional) — already under the guarded internal prefix and the gateway's `denyAll`.

- [ ] **Step 1:** `StoryAudienceQueryTest` on a real Postgres, set up like `DuplicateRelationshipRaceTest` (`@SpringBootTest`, `@Testcontainers`, `@ActiveProfiles("test")`, `PostgreSQLContainer("postgres:16-alpine")` with `@ServiceConnection`, `@MockitoBean KafkaTemplate<String, String>`). Seed rows straight through the repositories, not `FollowService` / `BlockService`: a block placed through the service also deletes the follows, which would hide whether the query filters blocks. Owner `1`, followed by: `2` plain; `3`, whom `1` follows back; `4`, blocked by `1`; `5`, who blocked `1`; `6`, whose profile is deactivated; `7`, who muted `1`; `8`, who muted `1` and un-muted (`deletedAt` set); `9`, whose follow is soft-deleted. Through `followService.storyAudience`:
  - `followersScopeIsEveryFollowerWhoMaySee` → `[2, 3, 8]`, `next` null.
  - `friendsScopeIsOnlyFollowersFollowedBack` → `[3]`.
  - `pagesByFollowerId` — size 2 → `[2, 3]`, next 3; after 3 → `[8]`, next null.
- [ ] **Step 2:** `./mvnw test -pl services/user-service -Dtest=StoryAudienceQueryTest` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** `./mvnw test -pl services/user-service` → PASS.
- [ ] **Step 5:** `docs/user-service-api.md` §3.11: notification-service is now a consumer of mute — a follower who muted an account gets no story notifications from it (via internal `GET /internal/story-audience`); story rings still show.
- [ ] **Step 6:** Commit `feat(user-service): page a story's audience for notification-service`.

### Task 6: notification-service STORY notifications

**Files:**
- Modify: `services/notification-service/src/main/java/com/tiktok/notificationservice/entity/NotificationType.java`
- Create: `.../client/StoryAnnouncementClient.java`, `.../client/StoryAudienceClient.java`, `.../event/consumer/StoryActivationEventConsumer.java`
- Modify: `.../config/DownstreamRestClientConfig.java`, `src/main/resources/application.yml`, `docker-compose.prod.yml` (recipe)
- Modify: `docs/story-service-api.md`, `CLAUDE.md` (recipe)
- Test: `NotificationTypeTest`, `NotificationServiceImplTest`; create `client/StoryAnnouncementClientTest`, `client/StoryAudienceClientTest`, `event/consumer/StoryActivationEventConsumerTest`

**Interfaces:**
- Consumes: `StoryActivatedEvent` (Task 1), the announcement endpoint (Task 4), the story-audience endpoint (Task 5).
- Produces:
  - `NotificationType.STORY` — actor and reference are the story's owner; `collapsesRepeats()` true.
  - `StoryAnnouncementClient(RestClient storyServiceRestClient)`: `Optional<Announcement> announcement(String storyId)`, `record Announcement(Long userId, String visibility)`. 404 → empty; every other failure propagates — no catch, unlike `BlockClient`, so kafka-lib retries until the story is confirmed.
  - `StoryAudienceClient(RestClient userServiceRestClient)`: `Page page(Long ownerId, String scope, Long after)`, `record Page(List<Long> userIds, Long next)`; always sends `size=500`, omits `after` when null; failures propagate.
  - `RestClient DownstreamRestClientConfig.storyServiceRestClient(@Value("${downstream.story-service-uri}") ...)` through the existing private `client(...)`.
  - `StoryActivationEventConsumer`: `@KafkaListener(topics = "story.activation-events", groupId = "notification-service") void onMessage(String payload)`.

- [ ] **Step 1:** Tests:
  - `NotificationTypeTest.storiesCollapseRepeats`.
  - `NotificationServiceImplTest.create_collapsesARepeatStoryFromTheSameOwnerWithinTheWindow` — the LIKE collapse test with `STORY`, actor 7, reference `"7"` → no save, no publish, no push.
  - `StoryAnnouncementClientTest` (`MockRestServiceServer`, as `VideoOwnerClientTest`): 200 `{"success":true,"data":{"userId":7,"visibility":"FRIENDS"}}` → present; 404 → empty; 500 → throws `HttpServerErrorException`.
  - `StoryAudienceClientTest`: expects `/api/v1/users/internal/story-audience?userId=7&scope=FRIENDS&after=3&size=500` and parses `{"userIds":[11,12],"next":12}`; with `after` null the parameter is absent.
  - `StoryActivationEventConsumerTest` (Mockito; real `IdempotentEventProcessor` over a mocked `ProcessedEventRepository`, as `FollowEventConsumerTest`):
    - `publicStoryNotifiesEveryFollowerAcrossPages` — announcement `(7, "PUBLIC")`; `page(7, "FOLLOWERS", null)` → `([11, 12], 12)`; `page(7, "FOLLOWERS", 12)` → `([13], null)` → `create(r, 7L, STORY, "New story", "Someone posted a new story.", "7")` for 11, 12 and 13.
    - `friendsStoryAsksForFriends` → `page(7, "FRIENDS", null)`.
    - `privateStoryNotifiesNobody`, `storyThatIsGoneNotifiesNobody` (empty announcement) → audience client and `create` never called.
    - `redeliveredEventDoesNothing` — `tryClaim` false → no client called.
    - `failureOnALaterPageRethrowsAndReleasesTheClaim` — page 2 throws `ResourceAccessException` → `onMessage` throws it, `releaseClaim(eventId)` is called, page 1's recipients were created once (their repeat on the retry is the collapse tested above).
- [ ] **Step 2:** `./mvnw test -pl services/notification-service` → FAIL.
- [ ] **Step 3:** Implement. The consumer parses, then inside `runOnce`: announcement empty → return; scope `PUBLIC` → `"FOLLOWERS"`, `FRIENDS` → `"FRIENDS"`, anything else → return; page from `after = null` until `next` is null; the owner is the announcement's `userId`. No block check — the audience endpoint already excludes blocks. `ponytail:` note on the loop: one lookup + insert + `notification.created` + push per recipient, in sequence; a bulk insert is the upgrade. `application.yml`: `story-service-uri: ${STORY_SERVICE_URI:http://localhost:8086}` under `downstream`. `docker-compose.prod.yml`: `STORY_SERVICE_URI: http://story-service:8086` in notification-service's `environment`.
- [ ] **Step 4:** `./mvnw test -pl services/notification-service` → PASS.
- [ ] **Step 5:** Docs. `docs/story-service-api.md`, a "Thông báo" paragraph: PUBLIC → followers, FRIENDS → friends, PRIVATE → nobody; never across a block, never to a follower who muted the owner, never for a hidden owner; one entry per owner per recipient per 24h; re-checked when sent. `CLAUDE.md`: a new sub-bullet right under the **Story** bullet — leave that bullet's text alone, it holds someone else's edit — covering `story.activation-events` (and why not `story.story-events`: media-worker's `StoryModerationConsumer` has no `eventType` routing), `activatedAt` / `announcedAt`, the re-check, the audience endpoint and the 24h collapse.
- [ ] **Step 6:** Commit `feat(notification-service): tell followers about a new story`.

### Task 7: web ring API and ring store

Repository: `../tiktok-cloned` (Tasks 7–10).

**Files:**
- Modify: `src/lib/api/types.ts`, `src/lib/api/stories.ts`
- Create: `src/lib/stories/ring-store.ts`, `src/lib/stories/__tests__/ring-store.test.ts`

**Interfaces:**
- Produces:
  - `interface StoryRing { userId: string; hasUnseen: boolean }` (types.ts).
  - `getStoryRings(userIds: readonly string[]): Promise<StoryRing[]>` — `apiFetch("/stories/rings", { query: { userIds: userIds.join(",") }, auth: "optional" })`, each `userId` passed through `String()` (short ids parse as numbers; see `resolveAuthors`).
  - In `ring-store.ts`: `type RingState = "unseen" | "seen" | "none"`; `MAX_RING_BATCH = 100`.
  - `ringStateOf(stories: readonly StoryResponse[], own: boolean): RingState` — empty → none; own: unseen iff some `status === "ACTIVE"`, else seen; other: unseen iff some `!viewedByMe`, else seen.
  - `createRingStore(options: { fetchRings: (userIds: string[]) => Promise<StoryRing[]>; tickMs?: number; ttlMs?: number; now?: () => number; schedule?: (run: () => void, ms: number) => void }): RingStore` — defaults 50 ms, 60 000 ms, `Date.now`, `setTimeout`.
  - `interface RingStore { get(userId: string): RingState | undefined; subscribe(userId: string, listener: () => void): () => void; set(userId: string, state: RingState): void; invalidate(userId: string): void }`. `get` is pure (a `useSyncExternalStore` snapshot). `subscribe` queues the id when it is unknown or older than `ttlMs` and not in flight; one flush per tick sends the queue in chunks of `MAX_RING_BATCH`. An id missing from an answer → "none"; a failed request → "none" for its ids, asked again after `ttlMs`. `set` stores a local answer as fresh. `invalidate` forgets the id and queues it again if anyone is subscribed. An id's listeners run whenever its state changes.

- [ ] **Step 1:** `ring-store.test.ts`, with a manual `schedule` that collects callbacks and a fake `now`:
  - `sends the ids subscribed in one tick as one request` — subscribe "1", "2", "1" → one `fetchRings(["1", "2"])`.
  - `splits more than 100 ids into chunks` — 250 ids → requests of 100, 100 and 50.
  - `an id missing from the answer has no ring` — answer `[{ userId: "1", hasUnseen: true }]` for `["1", "2"]` → "unseen" and "none", both listeners called.
  - `does not ask again until the answer is stale` — subscribing "1" again sends nothing; after `now` passes `ttlMs` it sends one request.
  - `a failed request shows no ring`.
  - `set turns an owner grey without asking`.
  - `invalidate asks again for a subscribed id`.
  - `ringStateOf` — own with ACTIVE → unseen; own with only PENDING_MODERATION / HELD → seen; another's unviewed → unseen; all viewed → seen; empty → none.
- [ ] **Step 2:** `npx vitest run src/lib/stories/__tests__/ring-store.test.ts` → FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Same command → PASS; `npm run typecheck` → PASS.
- [ ] **Step 5:** Commit `feat(stories): batch ring lookups for avatars`.

### Task 8: web `StoriesProvider`, `StoryAvatar`, own-ring rule

Check the Global Constraints' web precondition first.

**Files:**
- Create: `src/components/stories/StoriesProvider.tsx`, `src/components/stories/StoryAvatar.tsx`
- Create: `src/components/stories/__tests__/StoryAvatar.test.tsx`, `src/components/stories/__tests__/StoriesProvider.test.tsx`
- Modify: `src/components/stories/StoryRing.tsx`, `ProfileStoryAvatar.tsx`, `StoryViewer.tsx`, `StoryComposer.tsx`, `StoryOwnerPanel.tsx`; `src/app/layout.tsx`

**Interfaces:**
- Consumes: `createRingStore`, `ringStateOf`, `RingState` (Task 7); `getStoryRings`, `getUserStories`; `firstUnseen`, `markStorySeen`.
- Produces (the hooks live beside the provider, as `usePlayerSettings` does):
  - `StoriesProvider({ children })` — one `RingStore` per viewer (`useMemo` keyed on `useSession().user?.userId`) and the single `StoryViewer` that `openStories` opens.
  - `useStoryRing(userId: string): RingState` — `useSyncExternalStore` over the store; "none" for non-numeric ids (template profiles), while unknown, on the server, and outside a provider.
  - `useStories(): { openStories(author: Author): Promise<boolean>; syncRings(groups: readonly StoryGroup[]): void; invalidateOwnRing(): void }`. Outside a provider `openStories` resolves false and the others do nothing, so components tested alone (`StoryViewer.test.tsx`) still render.
    - `openStories` loads `getUserStories(author.userId)`. Empty or failed → `set(userId, "none")`, resolves false. Otherwise opens the viewer on the group `{ userId, stories, hasUnseen: ringStateOf(stories, own) === "unseen" }` at `firstUnseen`, resolves true; `onSeen` marks with `markStorySeen`, `onOwnStoryChanged` reloads, `onOwnStoryDeleted` and `onClose` close.
    - `syncRings(groups)` → `set(group.userId, ringStateOf(group.stories, group.userId === viewerId))` per group.
    - `invalidateOwnRing()` → `invalidate(viewerId)` when signed in.
  - `StoryRing` gains `thin?: boolean` (1px ring and 1px gap instead of 2px) and `fill?: boolean` (inner span `h-full w-full`, so the ring sits inside the caller's box). The defaults keep the profile header as it is.
  - `StoryAvatar({ author, className, thin, children }: { author: Author; className?: string; thin?: boolean; children: ReactNode })`. `children` is the avatar as drawn today (usually a profile link), rendered unchanged when the state is "none". Otherwise a `<button type="button">` labelled `Watch {nickname}'s stories`, classes `cn("flex-none rounded-full", className)`, holding `StoryRing` (`state`, `thin`, `fill`) around `<img src={author.avatarUrl || DEFAULT_AVATAR} alt="" referrerPolicy="no-referrer" className="h-full w-full rounded-full object-cover">`. Click: `preventDefault()` and `stopPropagation()`, then `openStories(author)`; false → `router.push(`/@${author.username}`)`. Enter / Space keydown: `stopPropagation()` (rows such as Activity's act on those keys themselves).

- [ ] **Step 1:** `StoryAvatar.test.tsx` (mock `@/components/stories/StoriesProvider` and `next/navigation`):
  - `renders the page's own avatar when there is no ring` — state "none" → the child link renders, no button.
  - `clicking the ring inside a link opens the stories and nothing else` — rendered inside `<a href="/x" onClick={spy}>`: clicking the button calls `openStories(author)`, not `spy`, and the click event is `defaultPrevented`.
  - `Enter on the ring does not reach the row` — inside `<div onKeyDown={rowSpy}>` → `rowSpy` not called.
  - `a ring whose stories are gone goes to the profile` — `openStories` resolves false → `push("/@bob")`.
- [ ] **Step 2:** `StoriesProvider.test.tsx` (mock `useSession` with a mutable user, and `@/lib/api/stories`):
  - `a new viewer never sees the previous viewer's rings` — as viewer "1", `getStoryRings` answers `[{ userId: "2", hasUnseen: true }]` and a probe using `useStoryRing("2")` shows "unseen"; set the session user to null and rerender → the probe shows "none" before the second answer arrives, and `getStoryRings` is asked again.
- [ ] **Step 3:** `npx vitest run src/components/stories` → FAIL.
- [ ] **Step 4:** Implement the provider, `StoryAvatar` and the `StoryRing` props. Mount `<StoriesProvider>` in `src/app/layout.tsx` inside `<PlayerSettingsProvider>`, around the page `div`, so `/video/[id]` and every shell route have it.
- [ ] **Step 5:** Wire the rest:
  - `ProfileStoryAvatar`: ring state and the group's `hasUnseen` come from `ringStateOf(stories, own)` — fixes the own ring that is always grey (spec §1).
  - `StoryViewer`: keep the latest `groups` in a ref and call `syncRings` with it on unmount. Every way a viewer closes (tray, profile header, provider) then turns the owners just watched grey everywhere.
  - `StoryComposer`: `invalidateOwnRing()` after `createStory` resolves, before `onPosted`.
  - `StoryOwnerPanel`: `invalidateOwnRing()` after `deleteStory` resolves.
- [ ] **Step 6:** `npm test` → PASS (the existing `StoryViewer.test.tsx` included); `npm run check` → PASS.
- [ ] **Step 7:** Commit `feat(stories): ringed avatars that open stories; own ring red while live`.

### Task 9: web rings where avatars appear (spec §5.4)

**Files** — in each, wrap the existing avatar element in `StoryAvatar`; `className` is the avatar's current box:
- `src/components/feed/ActionRail.tsx` (author `Link`, ~line 105): `className={compact ? "h-8 w-8" : "h-12 w-12"}`, `thin={compact}`. Covers For You, Following and `/video/[id]` (`BackendVideoDetail` renders `ActionRail`).
- `src/components/feed/comments/CommentItem.tsx` (author `Link`, ~line 170): `className={cn("self-start", isReply ? "h-6 w-6" : "h-8 w-8")}`, `thin`. Replies render through `CommentItem` too.
- `src/components/profile/FollowListModal.tsx` (`UserRow`'s `img`, ~line 357): `className="h-11 w-11"`. Covers the follower/following modal and the search page's Accounts tab (`SearchResults` renders `UserRow`).
- `src/components/layout/SearchDrawer.tsx` (account `img`, ~line 355): `author={authorFromProfile(result)}`, `className="h-10 w-10"`.
- `src/components/layout/side-nav/FollowingAccounts.tsx` (`AccountRow`'s `img`, ~line 133): `className={cn("ml-0.5", drawer ? "h-14 w-14" : "h-9 w-9")}` — the ring takes the `p-1` the picture already leaves for it. Covers the side-nav list and `FollowingDrawer`. Not the narrow stack: it is one button that opens the drawer.
- `src/components/layout/ActivityDrawer.tsx` (actor `img`, ~line 420, when `actor` is known): `author={authorFromProfile(actor)}`, `className="h-12 w-12"`.
- `src/components/following/SuggestedCreators.tsx` (`img`, ~line 121): `className="mb-[14px] h-12 w-12"`.
- `src/components/explore/ExploreGrid.tsx` (`img`, ~line 306): `className="h-6 w-6"`, `thin`.
- No ring (spec §3, and the "action UI" rule of §5.4): `TopBar`, the own avatar in `SideNav` / `NavRow`, `AccountListModal`, `ShareSheet`, `RepostBadge`, `FollowRequestsModal`, `CommentComposer`, `HeldComments` (the creator's moderation queue), `StoryViewer`, `StoryOwnerPanel`, `EditProfileModal`.

- [ ] **Step 1:** Wrap each place listed.
- [ ] **Step 2:** `npm test` → PASS (the comment, reply, search, follow-request and profile tests render without a provider, so without rings); `npm run check` → PASS.
- [ ] **Step 3:** Commit `feat(stories): story rings on avatars across the app`.

### Task 10: web STORY notifications

**Files:**
- Modify: `src/lib/api/notifications.ts`, `src/components/layout/ActivityDrawer.tsx`
- Create: `src/lib/api/__tests__/notifications.test.ts`

**Interfaces:**
- Consumes: `useStories().openStories` (Task 8).
- Produces: `NotificationType` gains `"STORY"`; `referencesUser("STORY")` is true — its `referenceId` is the owner's id, and without this `useNotifications` would look the owner's id up as a video.

- [ ] **Step 1:** `notifications.test.ts`: `referencesUser` is true for `STORY` and the three follow types, false for `LIKE`, `COMMENT`, `SHARE`, `SYSTEM`.
- [ ] **Step 2:** `npx vitest run src/lib/api/__tests__/notifications.test.ts` → FAIL.
- [ ] **Step 3:** Implement: the union, `referencesUser`, and STORY in the `referenceId` doc comment. `ActivityDrawer`: `describe()` gets `case "STORY": return "posted a new story";`. `openNotification` takes the row's `actor` (pass it at the `onOpen` call site); for STORY, ahead of the `referencesUser` branch: `openStories(actor ? authorFromProfile(actor) : unknownAuthor(item.referenceId))` → true: `onClose()`; false: `navigate(`/@${item.referenceId}`)`. Filters stay as they are: "All activity" already shows every type.
- [ ] **Step 4:** `npm test` and `npm run check` → PASS.
- [ ] **Step 5:** Commit `feat(notifications): story notifications open the stories`.

### Task 11: end-to-end check (no commit)

- [ ] **Step 1:** `make build` and `make infra-up`, then start api-gateway, user-, story-, notification-, video-service, media-worker and moderation-service with their `make run-*` / `make moderation-up` targets — never plain `./mvnw spring-boot:run`, which leaves the services without `JWT_SECRET`. Web: `npm run dev` in `../tiktok-cloned`.
- [ ] **Step 2:** Accounts A, B, C. B and C follow A; A follows C back (C is A's friend). A posts a FRIENDS image story → within ~10s it is ACTIVE with `activatedAt` and `announcedAt` set (`story_db.stories`), and `notification_db.notifications` holds one `STORY` entry for C (actor and reference = A) and none for B.
- [ ] **Step 3:** A posts a PUBLIC story → B gets one `STORY` entry; C gets no second one (collapsed). A third story → nothing new for either.
- [ ] **Step 4:** As B on the web: A's avatar is ringed red in the feed rail, comments, search, the side-nav Following list and its drawer, the follower modal and the Activity row. Clicking opens the viewer; after watching, every ring of A turns grey without a reload. The Activity entry reads "… posted a new story" and opens the stories; after A deletes them, it opens A's profile instead.
- [ ] **Step 5:** As A: the own avatar is grey right after posting (pending) and red once the story is ACTIVE.
- [ ] **Step 6:** Report anything that failed, with the step, before calling the work done.
