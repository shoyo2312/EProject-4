# Per-video analytics (TikTok Studio parity) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/studio/analytics/{videoId}` show what TikTok Studio shows — saves, new followers, previous-window change, hoverable Recharts charts with a retention insight, traffic sources, search queries and a viewer breakdown (new/returning, followers, age) — fed by data the platform really collects.

**Architecture:** The watch report, the follow call and the sign-up gain the fields the page needs (`source`/`searchQuery`, `sourceVideoId`, `birthDate`); they ride the existing Kafka events as nullable trailing fields into ClickHouse, where analytics-service grows the per-video query set. The frontend threads the watch source from each surface to `useWatchSession`, asks for a birthday once after sign-in, and rebuilds the Studio page on Recharts via shadcn's `chart`.

**Tech Stack:** Java 21 / Spring Boot 4.1 / Jackson 3 / Kafka / ClickHouse (JdbcTemplate, Testcontainers) / PostgreSQL + Flyway; Next.js 16 / React 19.2 / Tailwind / shadcn `chart` + Recharts 3 / Vitest + Testing Library.

**Spec:** `docs/superpowers/specs/2026-11-05-video-analytics-design.md` (this repo). The frontend half lives in the sibling repo `../tiktok-cloned`; paths below prefixed `web/` mean `../tiktok-cloned/`.

## Global Constraints

- Both repos are on `feat/suggested-accounts` with uncommitted work: cut `feat/video-analytics` from it in each repo before Task 1; do not touch the pre-existing uncommitted files.
- Kafka event records only gain **nullable trailing** fields. `libs/event-schema/src/test/resources/event-json.json` entries for a changed event are deleted and re-recorded (`-Devent-json.record=true`), and the diff reviewed; never hand-edit.
- ClickHouse changes are `ALTER TABLE … ADD COLUMN IF NOT EXISTS` / `CREATE TABLE IF NOT EXISTS` only, applied in **both** `services/analytics-service/src/main/resources/schema.sql` (runs on every boot) and `db/schema/clickhouse/analytics_db.sql`.
- Days and windows use `Asia/Ho_Chi_Minh` (`CreatorAnalyticsRepository.ZONE`); every read over an event table uses `FINAL`.
- Privacy: viewer breakdowns are null below `analytics.creator.min-audience` (default 100); a search query is listed only with ≥ 3 views; `birthDate` is returned to its owner only.
- `POST /interactions/videos/{id}/watch` without `source` keeps working (mobile app): stored as `OTHER`.
- Backend conventions from `CLAUDE.md`: constructor injection, records for DTOs, `DomainException` subclasses with a `code`, outbox via `UserEventPublisher.save`, Flyway `V{n}__…`.
- Backend tests: `./mvnw test -pl <module>` (ClickHouse/Postgres tests need Docker). Frontend: `npm test` in `web/`, `npm run lint`, `npx tsc --noEmit`.
- Colours and copy: blue `#0075db`, age groups `13-17, 18-24, 25-34, 35-44, 45-54, 55+, UNKNOWN`, traffic labels *For You, Following, Friends, Explore, Personal profile, Search, Messages, Direct link, Other*.

## Review Focus

1. A `SEARCH` watch whose `searchQuery` is blank, >100 chars after trim, or sent with a non-SEARCH source → stored as `''`, never 400 (Task 2).
2. A birth date exactly 13 years ago today is accepted; one day short of 13 years is rejected; `1899-12-31` and tomorrow are rejected — in both auth-service and user-service (Tasks 7, 4).
3. `/video/{id}?src=bogus` or no `src` → `DIRECT`; `?src=search` without `q` → `SEARCH` with no query (Task 15).
4. Retention with no drop at all (`[1,1,…,1]`) or with views = 0 → no insight line (Task 12).
5. Viewer breakdown above the threshold where nobody has a birth date → `ageGroups.UNKNOWN == total` and the other six are 0, not absent (Task 9c).

---

## Phase A — events and collection (backend)

### Task 1: Event schema — new fields and `UserBirthDateSetEvent`

**Files:**
- Modify: `libs/event-schema/src/main/java/com/tiktok/event/interaction/VideoWatchEvent.java`
- Modify: `libs/event-schema/src/main/java/com/tiktok/event/user/UserFollowChangedEvent.java`
- Modify: `libs/event-schema/src/main/java/com/tiktok/event/user/UserRegisteredEvent.java`
- Create: `libs/event-schema/src/main/java/com/tiktok/event/user/UserBirthDateSetEvent.java`
- Modify: `libs/event-schema/src/test/resources/event-json.json` (re-recorded)
- Test: `libs/event-schema/src/test/java/com/tiktok/event/EventJsonCompatibilityTest.java` (existing, no edit)

**Interfaces:**
- Produces:
  - `VideoWatchEvent(String eventId, Instant occurredAt, Long videoId, Long userId, long watchedMs, long durationMs, boolean completed, String source, String searchQuery)`; keep `of(videoId, userId, watchedMs, durationMs, completed)` (nulls) and add `of(videoId, userId, watchedMs, durationMs, completed, String source, String searchQuery)`.
  - `UserFollowChangedEvent(…, boolean requestApproved, Long sourceVideoId)`; keep both existing `of(...)` overloads (null source) and add `of(followerId, followingId, followed, requestApproved, Long sourceVideoId)`.
  - `UserRegisteredEvent(…, String avatarUrl, LocalDate birthDate)`; keep existing `of` overloads (null) and add `of(userId, username, email, avatarUrl, LocalDate birthDate)`.
  - `UserBirthDateSetEvent(String eventId, Instant occurredAt, Long userId, LocalDate birthDate) implements DomainEvent` with `of(userId, birthDate)`.

- [ ] **Step 1: Delete the three events' entries from `event-json.json`** (`VideoWatchEvent`, `UserFollowChangedEvent`, `UserRegisteredEvent`) so the change is a visible contract change.
- [ ] **Step 2: Run the compatibility test to see it fail**
  Run: `./mvnw test -pl libs/event-schema -Dtest=EventJsonCompatibilityTest`
  Expected: FAIL, "no pinned JSON for VideoWatchEvent" (and the other two).
- [ ] **Step 3: Add the fields and factories listed under Produces.** Javadoc on each new field: nullable, absent on events written before it existed. `LocalDate` serialises as `"2001-05-09"` under the services' mapper (java.time module is on by default in Jackson 3) — the recorded JSON must show a string, not an array.
- [ ] **Step 4: Re-record and review**
  Run: `./mvnw test -pl libs/event-schema -Dtest=EventJsonCompatibilityTest -Devent-json.record=true` then `git diff libs/event-schema/src/test/resources/event-json.json`
  Expected: four entries added/changed; the three changed ones differ from the deleted ones only by the new keys; `birthDate` is a quoted ISO string.
- [ ] **Step 5: Run the test again without the flag**
  Expected: PASS for every event, including `UserBirthDateSetEvent` reading `{}`.
- [ ] **Step 6: Install the lib so services see it**
  Run: `./mvnw install -DskipTests -pl libs/event-schema`
- [ ] **Step 7: Commit** — `feat(events): watch source, follow source video, birth date`

### Task 2: interaction-service — `source` and `searchQuery` on the watch report

**Files:**
- Create: `services/interaction-service/src/main/java/com/tiktok/interactionservice/dto/request/WatchSource.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/dto/request/WatchRequest.java`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/service/ViewServiceImpl.java:110-127`
- Modify: `services/interaction-service/src/main/java/com/tiktok/interactionservice/event/producer/InteractionEventPublisher.java:160-172`
- Modify: `docs/interaction-service-api.md` §3.9
- Test: `services/interaction-service/src/test/java/com/tiktok/interactionservice/service/ViewServiceImplWatchTest.java`

**Interfaces:**
- Produces: `enum WatchSource { FOR_YOU, FOLLOWING, FRIENDS, EXPLORE, PROFILE, SEARCH, MESSAGE, DIRECT, OTHER }`; `WatchRequest(Long watchedMs, Long durationMs, WatchSource source, @Size(max = 100) String searchQuery)` — `source` nullable; `InteractionEventPublisher.publishWatch(Long videoId, Long userId, long watchedMs, long durationMs, boolean completed, String source, String searchQuery)`.
- Consumes: Task 1's `VideoWatchEvent.of(…, source, searchQuery)`.

- [ ] **Step 1: Write the failing tests** in `ViewServiceImplWatchTest` (same Mockito setup as the file's existing tests; capture the publisher call with `ArgumentCaptor`):
  - `aMissingSourceIsStoredAsOther` — request `(5000L, 10000L, null, null)` → publisher called with `"OTHER", ""`.
  - `aSearchWatchKeepsItsQueryTrimmedAndLowerCased` — `(…, SEARCH, "  Tiny HOUSE ")` → `"SEARCH", "tiny house"`.
  - `aQueryOnANonSearchSourceIsDropped` — `(…, FOR_YOU, "tiny house")` → `"FOR_YOU", ""`.
  - `aBlankQueryIsDropped` — `(…, SEARCH, "   ")` → `"SEARCH", ""`.
- [ ] **Step 2: Run** `./mvnw test -pl services/interaction-service -Dtest=ViewServiceImplWatchTest` — Expected: compile failure (no 4-arg `WatchRequest`).
- [ ] **Step 3: Implement.** `WatchSource` enum; `WatchRequest` gains the two components; `ViewServiceImpl.recordWatch` computes `String source = request.source() == null ? "OTHER" : request.source().name()` and `String query = request.source() == WatchSource.SEARCH && request.searchQuery() != null ? request.searchQuery().strip().toLowerCase(Locale.ROOT) : ""`; publisher passes both into `VideoWatchEvent.of(…)`. A present-but-unknown `source` string is a Jackson enum error → the existing 400 path (confirm once with a `curl` against the running service; note the code in the doc).
- [ ] **Step 4: Run the test class** — Expected: PASS (all tests in the file, old and new).
- [ ] **Step 5: Doc** — in `docs/interaction-service-api.md` §3.9 add `source` (optional, the nine values, "thiếu → OTHER") and `searchQuery` (optional, ≤100, only with SEARCH).
- [ ] **Step 6: Commit** — `feat(interaction): watch report carries source and search query`

### Task 3a: analytics-service — `source`/`search_query` columns on `watch_events`

**Files:**
- Modify: `services/analytics-service/src/main/resources/schema.sql` (append after the `watch_events` CREATE)
- Modify: `db/schema/clickhouse/analytics_db.sql`
- Modify: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/repository/TrainingDataRepository.java:25-44`
- Modify: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/event/consumer/VideoWatchEventConsumer.java`
- Test: `services/analytics-service/src/test/java/com/tiktok/analyticsservice/repository/CreatorAnalyticsRepositoryTest.java` (helper `watch(...)` gains source/query params), `services/analytics-service/src/test/java/com/tiktok/analyticsservice/event/consumer/TrainingSinkConsumerTest.java`

**Interfaces:**
- Produces: `TrainingDataRepository.WatchRow(String eventId, String videoId, Long userId, long watchedMs, long durationMs, boolean completed, Instant occurredAt, String source, String searchQuery)`; columns `watch_events.source LowCardinality(String) DEFAULT 'OTHER'`, `watch_events.search_query String DEFAULT ''`.

- [ ] **Step 1: Schema.** Append to both SQL files:
  ```sql
  ALTER TABLE watch_events ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT 'OTHER';
  ALTER TABLE watch_events ADD COLUMN IF NOT EXISTS search_query String DEFAULT '';
  ```
  (`analytics_db.sql` uses the `analytics_db.` prefix like its CREATEs.)
- [ ] **Step 2: Write the failing test** in `TrainingSinkConsumerTest`: `aWatchEventWritesItsSourceAndQuery` — feed the consumer a `VideoWatchEvent` JSON with `"source":"SEARCH","searchQuery":"tiny house"` and assert the captured `WatchRow` has them; `anOldWatchEventWithoutSourceIsOther` — JSON without the keys → row `source == "OTHER"`, `searchQuery == ""`.
- [ ] **Step 3: Run** `./mvnw test -pl services/analytics-service -Dtest=TrainingSinkConsumerTest` — Expected: compile failure.
- [ ] **Step 4: Implement.** `WatchRow` gains the two components; `insertWatches` INSERT lists `source, search_query`; the consumer maps `event.source() == null ? "OTHER" : event.source()` and `event.searchQuery() == null ? "" : event.searchQuery()`.
- [ ] **Step 5: Run the consumer test and the repository test** (`-Dtest=TrainingSinkConsumerTest,CreatorAnalyticsRepositoryTest`; Docker required) — Expected: PASS; the repository test's `watch(...)` helper passes `"OTHER", ""`.
- [ ] **Step 6: Seed compatibility check.** With the local stack up (`make infra-up`), after the service has applied the ALTERs:
  Run: `docker exec -i tt-clickhouse clickhouse-client --password clickhouse -d analytics_db --query "INSERT INTO watch_events FORMAT Native" < ~/seed-export/clickhouse/watch_events.native && docker exec tt-clickhouse clickhouse-client --password clickhouse -d analytics_db --query "SELECT source, count() FROM watch_events GROUP BY source"`
  Expected: the insert succeeds and every seeded row reports `OTHER`. If ClickHouse rejects a Native block with fewer columns, add `--input_format_defaults_for_omitted_fields=1` to the seed import notes in `docs/operations.md` and record the flag here.
- [ ] **Step 7: Commit** — `feat(analytics): store watch source and search query`

### Task 3b: analytics-service — saves

**Files:**
- Create: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/event/consumer/VideoSavedEventConsumer.java`
- Test: `services/analytics-service/src/test/java/com/tiktok/analyticsservice/event/consumer/VideoSavedEventConsumerTest.java`

**Interfaces:**
- Produces: rows in `engagement_events` with `event_type` `SAVED` / `UNSAVED` (same `EngagementEventRepository.Row` as likes).

- [ ] **Step 1: Write the failing test** (mirror `CommentCreatedEventConsumerTest`'s batch style): `aSaveIsSavedAndAnUnsaveIsUnsaved` — two `VideoSavedEvent` records, `saved=true` and `saved=false` → `insertAll` receives rows typed `SAVED` then `UNSAVED` with the event's `videoId` as a string and its `userId`.
- [ ] **Step 2: Run** `-Dtest=VideoSavedEventConsumerTest` — Expected: FAIL, class not found.
- [ ] **Step 3: Implement** `VideoSavedEventConsumer` as a copy of `VideoLikeEventConsumer` on topic `interaction.save-events`, `containerFactory = KafkaBatchConfig.BATCH_FACTORY`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(analytics): consume save events`

### Task 3c: analytics-service — `source_video_id` on `follow_events` + backfill topic

**Files:**
- Modify: both schema files (`follow_events` ALTER; `analytics_db.sql` has no `follow_events` yet — add the full CREATE from `schema.sql` plus the column)
- Modify: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/repository/CreatorAnalyticsRepository.java:68-72` (`insertFollow`)
- Modify: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/event/consumer/FollowEventConsumer.java`
- Test: `services/analytics-service/src/test/java/com/tiktok/analyticsservice/event/consumer/FollowEventConsumerTest.java`

**Interfaces:**
- Produces: `insertFollow(String eventId, long followerId, long followingId, boolean followed, Instant occurredAt, String sourceVideoId)` (`""` when none); column `follow_events.source_video_id String DEFAULT ''`; `FollowEventConsumer` listens on `{"user.follow-events", "user.follow-backfill-events"}`.

- [ ] **Step 1: Schema** — `ALTER TABLE follow_events ADD COLUMN IF NOT EXISTS source_video_id String DEFAULT '';` in both files (+ the CREATE in `analytics_db.sql`).
- [ ] **Step 2: Failing tests** in `FollowEventConsumerTest`: `aFollowFromAVideoKeepsTheVideo` — JSON with `"sourceVideoId": 42` → `insertFollow(…, "42")`; `aFollowWithoutASourceStoresEmpty` — no key → `""`.
- [ ] **Step 3: Run** `-Dtest=FollowEventConsumerTest` — Expected: compile failure.
- [ ] **Step 4: Implement** — repository INSERT gains the column; consumer maps `event.sourceVideoId() == null ? "" : String.valueOf(event.sourceVideoId())`; `@KafkaListener(topics = {"user.follow-events", "user.follow-backfill-events"}, …)`.
- [ ] **Step 5: Run** `-Dtest=FollowEventConsumerTest,CreatorAnalyticsRepositoryTest` — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(analytics): follow source video, backfill topic`

### Task 3d: analytics-service — `viewer_profiles` dimension

**Files:**
- Modify: both schema files
- Create: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/repository/ViewerProfileRepository.java`
- Modify: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/event/consumer/UserRegisteredEventConsumer.java`
- Create: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/event/consumer/UserBirthDateSetEventConsumer.java`
- Test: `services/analytics-service/src/test/java/com/tiktok/analyticsservice/event/consumer/UserRegisteredEventConsumerTest.java` (create), `…/UserBirthDateSetEventConsumerTest.java` (create)

**Interfaces:**
- Produces: table `viewer_profiles (user_id Int64, birth_date Nullable(Date), updated_at DateTime64(3)) ENGINE = ReplacingMergeTree(updated_at) ORDER BY user_id`; `ViewerProfileRepository.upsert(long userId, LocalDate birthDate, Instant updatedAt)`.

- [ ] **Step 1: Schema** — the CREATE above (`IF NOT EXISTS`) in both files, with a comment: one row per viewer, latest `updated_at` wins, read with `FINAL`.
- [ ] **Step 2: Failing tests**: `UserRegisteredEventConsumerTest.aRegistrationWithABirthDateFillsViewerProfiles` (JSON with `"birthDate":"2001-05-09"` → `upsert(userId, LocalDate.of(2001,5,9), occurredAt)`) and `…WithoutOneOnlyWritesTheSignup` (no upsert); `UserBirthDateSetEventConsumerTest.aLaterBirthDateUpdatesTheViewer` (topic `user.profile-events`).
- [ ] **Step 3: Run** both — Expected: FAIL / compile failure.
- [ ] **Step 4: Implement** the repository (single `INSERT INTO viewer_profiles (user_id, birth_date, updated_at) VALUES (?, ?, ?)`), the new consumer (record-at-a-time like `FollowEventConsumer`, skip when `userId` or `birthDate` null), and the registered consumer's extra upsert.
- [ ] **Step 5: Run** — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(analytics): viewer birth dates`

### Task 4: user-service — `birth_date`, once-only, owner-only, announced

**Files:**
- Create: `libs/common-lib/src/main/java/com/tiktok/common/validation/BirthDateRule.java` (+ test `libs/common-lib/src/test/java/com/tiktok/common/validation/BirthDateRuleTest.java`)
- Create: `services/user-service/src/main/resources/db/migration/V10__add_birth_date_and_follow_source.sql`
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/entity/UserProfile.java`, `entity/FollowRequest.java`
- Modify: `…/dto/request/UpdateProfileRequest.java`, `…/dto/response/UserProfileResponse.java`, `…/mapper/UserProfileMapper.java`
- Create: `…/exception/BirthDateAlreadySetException.java`, `…/exception/InvalidBirthDateException.java`
- Modify: `…/service/UserProfileService.java`, `…/service/UserProfileServiceImpl.java:222-275`
- Modify: `…/event/consumer/UserRegisteredEventConsumer.java`, `…/event/producer/UserEventPublisher.java`, `…/event/producer/OutboxPublisher.java:30`
- Modify: `docs/user-service-api.md` §3.1/§3.2
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/UserProfileServiceImplTest.java`

**Interfaces:**
- Produces:
  - `BirthDateRule.isAllowed(LocalDate birthDate, LocalDate today): boolean` — true iff `birthDate != null && !birthDate.isBefore(LocalDate.of(1900,1,1)) && !birthDate.isAfter(today.minusYears(13))`.
  - Migration: `ALTER TABLE user_profiles ADD COLUMN birth_date DATE; ALTER TABLE user_follow_requests ADD COLUMN source_video_id BIGINT;`
  - `UserProfile.getBirthDate(): LocalDate`, `UserProfile.setBirthDate(LocalDate)`; `FollowRequest.getSourceVideoId(): Long` (builder field, used by Task 5).
  - `UpdateProfileRequest(…, LocalDate birthDate)`; `UserProfileResponse(…, LocalDate birthDate)` — mapper's `toOwnResponse` maps it, `toResponse` sets null.
  - `UserProfileService.createFromRegisteredEvent(Long userId, String username, String avatarUrl, LocalDate birthDate)`.
  - `UserEventPublisher.publishBirthDateSet(Long userId, LocalDate birthDate)` → aggregate `USER_PROFILE` → topic `user.profile-events`.
  - Errors: 409 `BIRTH_DATE_ALREADY_SET`, 400 `BIRTH_DATE_INVALID`.

- [ ] **Step 1: `BirthDateRuleTest`** — `exactlyThirteenTodayIsAllowed`, `oneDayShortOfThirteenIsNot`, `before1900IsNot`, `tomorrowIsNot`, `nullIsNot` (fix `today = 2026-11-05`). Run, see FAIL, implement, PASS, `./mvnw install -DskipTests -pl libs/common-lib`.
- [ ] **Step 2: Failing service tests** in `UserProfileServiceImplTest` (Testcontainers Postgres like the file's others):
  - `aBirthDateCanBeSetOnce` — PATCH with `2001-05-09` → response `birthDate` equal; second PATCH with another date → `BirthDateAlreadySetException`.
  - `anUnderageBirthDateIsRejected` — `today.minusYears(13).plusDays(1)` → `InvalidBirthDateException`, nothing stored.
  - `settingABirthDateIsAnnounced` — one `outbox_events` row with `event_type = "UserBirthDateSetEvent"`, aggregate `USER_PROFILE`.
  - `theBirthDateIsTheOwnersOnly` — `getProfile(viewer = someone else)` → `birthDate == null`; own → set.
  - `aRegistrationCarriesTheBirthDate` — `createFromRegisteredEvent(…, LocalDate)` stores it.
- [ ] **Step 3: Run** `./mvnw test -pl services/user-service -Dtest=UserProfileServiceImplTest` — Expected: compile failure.
- [ ] **Step 4: Implement** the list under Produces. In `updateOwnProfile`: if `request.birthDate() != null` → `if (profile.getBirthDate() != null) throw new BirthDateAlreadySetException(); if (!BirthDateRule.isAllowed(…, LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")))) throw new InvalidBirthDateException(); profile.setBirthDate(…); userEventPublisher.publishBirthDateSet(userId, birthDate);` — same transaction as the row. `UserRegisteredEventConsumer` passes `event.birthDate()`. `OutboxPublisher.TOPICS` gains `USER_PROFILE → "user.profile-events"`.
- [ ] **Step 5: Run** `./mvnw test -pl services/user-service` — Expected: PASS (`ddl-auto: validate` passes with the migration).
- [ ] **Step 6: Doc** — `docs/user-service-api.md`: `birthDate` on §3.1 (owner-only), on §3.2 PATCH (once; 409/400 codes).
- [ ] **Step 7: Commit** — `feat(user): birth date, set once, owner-only`

### Task 5: user-service — `sourceVideoId` on follow, through requests

**Files:**
- Modify: `services/user-service/src/main/java/com/tiktok/userservice/controller/FollowController.java:31-39`
- Create: `…/dto/request/FollowRequestBody.java`
- Modify: `…/service/FollowService.java`, `…/service/FollowServiceImpl.java:57-91, 249-306`
- Modify: `…/event/producer/UserEventPublisher.java:42-52`
- Modify: `docs/user-service-api.md` (follow endpoint)
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/FollowServiceImplTest.java`

**Interfaces:**
- Produces: `record FollowRequestBody(Long sourceVideoId)`; `POST /users/{id}/follow` with `@RequestBody(required = false) FollowRequestBody body`; `FollowService.follow(Long followerId, Long followingId, Long sourceVideoId)` (old 2-arg stays, delegates null); `UserEventPublisher.publishFollowChanged(Long followerId, Long followingId, boolean followed, boolean requestApproved, Long sourceVideoId)`.
- Consumes: Task 4's `FollowRequest.sourceVideoId` column.

- [ ] **Step 1: Failing tests** in `FollowServiceImplTest`:
  - `aFollowFromAVideoAnnouncesTheVideo` — public target, `follow(a, b, 42L)` → outbox payload contains `"sourceVideoId":42`.
  - `aFollowRequestKeepsTheVideoUntilApproval` — private target, `follow(a, b, 42L)` → `user_follow_requests.source_video_id == 42`; `approveFollowRequest(b, a)` → outbox `UserFollowChangedEvent` with `"sourceVideoId":42`.
  - `anUnfollowCarriesNoVideo` — payload `"sourceVideoId":null`.
- [ ] **Step 2: Run** `-Dtest=FollowServiceImplTest` — Expected: compile failure.
- [ ] **Step 3: Implement**: controller body → service; `request()` stores it on the `FollowRequest` builder; `addEdge(followerId, followingId, requestApproved, sourceVideoId)`; `approve(List<FollowRequest>)` passes `request.getSourceVideoId()`; publisher overload builds `UserFollowChangedEvent.of(…, sourceVideoId)`.
- [ ] **Step 4: Run** — Expected: PASS (whole class).
- [ ] **Step 5: Doc** — optional body `{ "sourceVideoId" }` on the follow endpoint.
- [ ] **Step 6: Commit** — `feat(user): attribute follows to the video they came from`

### Task 6: user-service — follow-graph backfill

**Files:**
- Create: `services/user-service/src/main/java/com/tiktok/userservice/service/FollowGraphBackfillService.java`
- Modify: `…/controller/InternalFollowController.java` (new endpoint), `…/event/producer/UserEventPublisher.java`, `…/event/producer/OutboxPublisher.java:30`
- Modify: `…/repository/UserFollowRepository.java` (keyset page by id)
- Modify: `docs/user-service-api.md` (internal section), `docs/operations.md` (when to run it)
- Test: `services/user-service/src/test/java/com/tiktok/userservice/service/FollowGraphBackfillServiceTest.java`

**Interfaces:**
- Produces: `POST /api/v1/users/internal/follow-backfill` (internal token, already `denyAll` at the gateway under `/api/v1/users/internal/**`) → `{ "published": n }`; `FollowGraphBackfillService.publishAll(): long`; `UserEventPublisher.publishFollowBackfill(UserFollowChangedEvent event)` → aggregate `USER_FOLLOW_BACKFILL` → topic `user.follow-backfill-events`; `UserFollowRepository.findByIdGreaterThanAndDeletedAtIsNullOrderByIdAsc(Long id, Pageable)`.

- [ ] **Step 1: Failing tests**: `everyLiveEdgeIsPublishedOnceWithAStableId` — 3 edges (one soft-deleted) → 2 outbox rows, aggregate `USER_FOLLOW_BACKFILL`, payload `eventId == "backfill-follow-" + edge.id`, `occurredAt == edge.createdAt`, `followed == true`, `sourceVideoId == null`; `runningTwiceWritesTheSameIds` — ids identical across runs (ClickHouse dedups; the outbox will carry duplicates, which is accepted).
- [ ] **Step 2: Run** — Expected: FAIL.
- [ ] **Step 3: Implement**: page 500 edges per `TransactionTemplate` execution (keyset on id), build `new UserFollowChangedEvent("backfill-follow-" + edge.getId(), edge.getCreatedAt(), edge.getFollowerId(), edge.getFollowingId(), true, false, null)`, publish; endpoint delegates and returns the count.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Docs** — endpoint in `user-service-api.md` internal section; `operations.md`: run once after deploying Task 3c, and after any seed restore that predates `follow_events.native`.
- [ ] **Step 6: Commit** — `feat(user): one-off follow graph backfill for analytics`

### Task 7: auth-service — `birthDate` on sign-up completion

**Files:**
- Modify: `services/auth-service/src/main/java/com/tiktok/authservice/dto/request/CompleteSignupRequest.java`
- Create: `…/exception/InvalidBirthDateException.java` (400 `BIRTH_DATE_INVALID`)
- Modify: `…/service/AuthServiceImpl.java:180-195, 670-698`
- Modify: `docs/auth-service-api.md` (`/register/complete`)
- Test: `services/auth-service/src/test/java/com/tiktok/authservice/service/EmailFirstSignupTest.java`

**Interfaces:**
- Produces: `CompleteSignupRequest(String signupToken, String username, String password, @NotNull LocalDate birthDate)`; `createPasswordAccount(String username, String email, String password, boolean emailVerified, LocalDate birthDate)` (legacy `register()` passes null); `UserRegisteredEvent.of(id, username, email, null, birthDate)`.
- Consumes: `BirthDateRule` (Task 4 Step 1).

- [ ] **Step 1: Failing tests**: update the existing `new CompleteSignupRequest(ticket, "newcomer", "Password1!")` calls to pass `LocalDate.of(2001, 5, 9)`; add `theRegistrationEventCarriesTheBirthDate` (outbox payload has `"birthDate":"2001-05-09"`), `anUnderageSignupIsRejectedAndTheTicketSurvives` (`today.minusYears(13).plusDays(1)` → `InvalidBirthDateException`; a second call with a valid date succeeds), `exactlyThirteenIsAccepted`.
- [ ] **Step 2: Run** `-Dtest=EmailFirstSignupTest` — Expected: compile failure.
- [ ] **Step 3: Implement**: gate at the top of `completeSignup` (before the ticket is spent), using `LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))`.
- [ ] **Step 4: Run** `./mvnw test -pl services/auth-service` — Expected: PASS.
- [ ] **Step 5: Doc** — `birthDate` required on `/register/complete`, 400 `BIRTH_DATE_INVALID`, rule "≥ 13 tuổi".
- [ ] **Step 6: Commit** — `feat(auth): sign-up stores the birth date`

### Task 8: api-gateway — `birthDate` on `GET /me`

**Files:**
- Modify: `services/api-gateway/src/main/java/com/tiktok/apigateway/dto/ProfileMeResponse.java`, `…/dto/MeResponse.java`
- Test: the existing `MeResponse` test if present (`grep -rl MeResponse services/api-gateway/src/test`), else add `MeResponseTest.birthDatePassesThroughAndIsNullWithoutAProfile`.

- [ ] **Step 1: Failing test** — `MeResponse.from(account, Optional.of(profile with birthDate))` → `birthDate` equal; `Optional.empty()` → null.
- [ ] **Step 2: Run** — Expected: compile failure.
- [ ] **Step 3: Implement** — `ProfileMeResponse.birthDate: LocalDate`; `MeResponse.birthDate: LocalDate` as the last component.
- [ ] **Step 4: Run** `./mvnw test -pl services/api-gateway` — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(gateway): /me carries birthDate`

## Phase B — the per-video query (backend)

### Task 9a: analytics-service — saves, new followers, `current`/`previous`

**Files:**
- Modify: `services/analytics-service/src/main/java/com/tiktok/analyticsservice/dto/response/CreatorVideoDetailResponse.java`, `CreatorDailyStatsResponse.java`, `CreatorTotalsResponse.java`, `CreatorVideoStatsResponse.java`
- Create: `…/dto/response/CreatorVideoTotalsResponse.java`
- Modify: `…/repository/CreatorAnalyticsRepository.java` (`DailyEngagement`, `EngagementTotals`, `dailyEngagement`, `engagementTotals`, new `followsFromVideo`, `dailyFollowsFromVideo`)
- Modify: `…/service/CreatorAnalyticsServiceImpl.java` (`loadVideo`, `daily`, `totals`, `stats`)
- Modify: `docs/tiktok-studio-api.md` §2.1–2.3
- Test: `CreatorAnalyticsRepositoryTest`, `CreatorAnalyticsServiceImplTest`

**Interfaces:**
- Produces:
  - `DailyEngagement(LocalDate day, long likes, long comments, long shares, long saves)`; `EngagementTotals(String videoId, long likes, long comments, long shares, long saves)` — `saves = countIf(SAVED) − countIf(UNSAVED)`.
  - `followsFromVideo(String videoId, Instant from, Instant to): long` — `countIf(followed = 1)` where `source_video_id = ?`; `dailyFollowsFromVideo(String videoId, Instant from, Instant to): List<DailyFollows>` (`lost` always 0).
  - `CreatorDailyStatsResponse(day, views, likes, comments, shares, saves, followersGained, followersLost)`; `CreatorTotalsResponse(views, likes, comments, shares, saves, netFollowers)`; `CreatorVideoStatsResponse(…, shares, saves)`.
  - `CreatorVideoTotalsResponse(long views, long likes, long comments, long shares, long saves, long newFollowers)`.
  - `CreatorVideoDetailResponse(videoId, views, viewers, totalWatchMs, avgWatchMs, completionRate, likes, comments, shares, saves, newFollowers, retention, daily, current, previous, trafficSources, searchQueries, viewers)` — the last three typed in 9b/9c; until then pass `List.of()`, `List.of()`, `null`. `newFollowers` lifetime = `followsFromVideo(videoId, EPOCH, window.to)`.
- Frontend reads these names verbatim (Task 11).

- [ ] **Step 1: Failing repository tests**: `savesAreNetLikeLikes` (SAVED, SAVED, UNSAVED on `mine` → `engagementTotals` saves 1, `dailyEngagement` day saves 1); `followsFromTheVideoAreCountedGross` (two follows with `source_video_id='mine'`, one unfollow, one follow from `''` → 2).
- [ ] **Step 2: Failing service tests** in `CreatorAnalyticsServiceImplTest` (mocked repository): `theVideoPageTotalsBothWindows` — `current` sums the window's daily, `previous` the window before (repository called with `previousFrom..from`); `savesRideEveryTotal`.
- [ ] **Step 3: Run** both — Expected: compile failure.
- [ ] **Step 4: Implement** — SQL adds `countIf(event_type = 'SAVED') - countIf(event_type = 'UNSAVED') AS saves`; `loadVideo` runs `watchTotals`, `engagementTotals`, `followsFromVideo` for `[from,to)` and `[previousFrom,from)`, builds the two totals; `daily(...)` now receives `dailyFollowsFromVideo` for the video page.
- [ ] **Step 5: Run** `./mvnw test -pl services/analytics-service` — Expected: PASS (controller test's JSON fixtures updated for the new fields).
- [ ] **Step 6: Doc** — §2.3 JSON gains `saves`, `newFollowers`, `current`, `previous`, `daily.saves`; §2.1/§2.2 note `saves`; the "followersGained luôn 0" sentence becomes "là follow đến từ video này".
- [ ] **Step 7: Commit** — `feat(analytics): saves, follows from a video, previous window`

### Task 9b: analytics-service — traffic sources and search queries

**Files:**
- Create: `…/dto/response/TrafficSourceResponse.java`, `…/dto/response/SearchQueryResponse.java`
- Modify: `CreatorAnalyticsRepository.java`, `CreatorAnalyticsServiceImpl.java`, `CreatorVideoDetailResponse.java`, `docs/tiktok-studio-api.md` §2.3
- Test: `CreatorAnalyticsRepositoryTest`, `CreatorAnalyticsServiceImplTest`

**Interfaces:**
- Produces: `record TrafficSourceResponse(String source, long views)`; `record SearchQueryResponse(String query, long views)`; `trafficSources(String videoId, Instant from, Instant to): List<TrafficSourceResponse>` (only `views > 0`, `ORDER BY views DESC`); `searchQueries(String videoId, Instant from, Instant to, int minViews, int limit): List<SearchQueryResponse>` (`source = 'SEARCH' AND search_query != ''`, `HAVING views >= ?`, `LIMIT ?`); service passes `3, 10`. Property `analytics.creator.search-query-min-views` default 3 (constructor `@Value`, like `minAudience`).

- [ ] **Step 1: Failing repository tests**: `trafficSourcesCountTheWindowsViewsBySource` (3 FOR_YOU, 1 SEARCH, 1 outside the window → `[FOR_YOU 3, SEARCH 1]`); `aSearchQueryNeedsThreeViews` (query A ×3, query B ×2 → only A); `queriesComeMostViewedFirstAndAtMostTen`.
- [ ] **Step 2: Service test**: `trafficAndQueriesAreTheWindows` — repository called with `window.from/to`, not EPOCH.
- [ ] **Step 3: Run** — Expected: compile failure.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** — Expected: PASS.
- [ ] **Step 6: Doc** — `trafficSources` (nine values incl. `OTHER`, chỉ nguồn > 0), `searchQueries` (≤10, ≥3 lượt).
- [ ] **Step 7: Commit** — `feat(analytics): traffic sources and search queries per video`

### Task 9c: analytics-service — viewer breakdown with age groups

**Files:**
- Create: `…/dto/response/CreatorVideoViewersResponse.java`
- Modify: `CreatorAnalyticsRepository.java`, `CreatorAnalyticsServiceImpl.java`, `CreatorVideoDetailResponse.java`, `docs/tiktok-studio-api.md` §2.3
- Test: `CreatorAnalyticsRepositoryTest`, `CreatorAnalyticsServiceImplTest`

**Interfaces:**
- Produces:
  - `CreatorVideoViewersResponse(long total, long newViewers, long previousTotal, long previousNewViewers, boolean breakdownAvailable, Long returning, Long followers, Long nonFollowers, Map<String, Long> ageGroups)` — the four nullable fields null when `breakdownAvailable` is false; `ageGroups` keys exactly `13-17, 18-24, 25-34, 35-44, 45-54, 55+, UNKNOWN`, all seven always present.
  - Repository: `newViewersOfVideo(long ownerId, String videoId, Instant from, Instant to): long` (the `newViewersByVideo` query with `HAVING … AND first_video = ?`); `followerViewers(long ownerId, String videoId, Instant from, Instant to): long` — distinct in-window viewers whose latest `follow_events` row toward `ownerId` with `occurred_at < to` has `followed = 1`; `viewersByAge(String videoId, Instant from, Instant to): Map<String, Long>` — per distinct viewer, age at their first in-window watch (`dateDiff('year', birth_date, min(occurred_at))` via `LEFT JOIN viewer_profiles FINAL`), bucketed with `multiIf`, null birth date → `UNKNOWN`.
  - Service: `viewers` block built from `watchTotals(...).viewers()` (window), `newViewersOfVideo`, the previous window's two numbers, and the three breakdowns only when `total >= minAudience`.

- [ ] **Step 1: Failing repository tests**: `newViewersOfAVideoAreThoseWhoseFirstWatchWasIt` (viewer X: first watch `mine`, then `other-mine` in window → counted for `mine` only); `followerViewersUseTheStateAtTheWindowsEnd` (follow then unfollow before `to` → non-follower; unfollow after `to` → follower); `ageGroupsBucketAtTheFirstInWindowWatch` (birth dates making 17/18/34/35/54/55 → `13-17, 18-24, 25-34, 35-44, 45-54, 55+` each 1); `viewersWithoutABirthDateAreUnknown` (Review Focus 5: `UNKNOWN == total`, six zeros present).
- [ ] **Step 2: Service tests**: `breakdownsAreWithheldUnderTheThreshold` (`minAudience = 100`, total 99 → nulls, `breakdownAvailable=false`, no breakdown query run); `returningIsTotalMinusNew`.
- [ ] **Step 3: Run** — Expected: compile failure.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** `./mvnw test -pl services/analytics-service` — Expected: PASS.
- [ ] **Step 6: Doc** — §2.3 `viewers` block, threshold note shared with §2.4.
- [ ] **Step 7: Commit** — `feat(analytics): per-video viewer breakdown`

## Phase C — frontend

### Task 10: Recharts via shadcn, `StudioLineChart`, `MetricTabs` on it

**Files:**
- Create (generated): `web/src/components/ui/chart.tsx`; `web/package.json` gains `recharts`
- Create: `web/src/components/studio/charts/StudioLineChart.tsx`
- Modify: `web/src/components/studio/MetricTabs.tsx` (drop `LineChart` + `CHART_W/H`), `web/src/lib/studio/metrics.ts` (remove `linePath`), `web/src/lib/studio/__tests__/metrics.test.ts`
- Test: `web/src/components/studio/__tests__/StudioLineChart.test.tsx` (create)

**Interfaces:**
- Produces: `StudioLineChart({ points, formatValue, formatLabel, height, yDomain }: { points: { label: string; value: number }[]; formatValue?: (v: number) => string; formatLabel?: (label: string, index: number) => string; height?: number; yDomain?: [number, number] })` — Recharts `AreaChart` inside `ChartContainer`: line `#0075db` 2px, gradient fill `#0075db` 0.18→0, three horizontal gridlines (`0, max/2, max` where `max = yDomain?.[1] ?? niceMax(...)`), X ticks from `labelIndexes(points.length)`, `Tooltip` with `cursor={{ stroke: "rgba(0,0,0,0.3)", strokeDasharray: "4 4" }}`, `activeDot` r=4 white fill blue stroke, custom content card: label line (`formatLabel`) over value line (`formatValue`).

- [ ] **Step 1:** `cd web && npx shadcn@latest add chart` → commit the generated file and lockfile. Expected: `recharts` ^3 in `package.json`, `src/components/ui/chart.tsx` present, `npm test` still green.
- [ ] **Step 2: Failing test** `StudioLineChart.test.tsx` (give the chart a fixed size: mock `ResizeObserver` and pass explicit `width`/`height` to the chart; Recharts' `ResponsiveContainer` measures 0×0 in jsdom): `drawsOneTickPerLabelIndex` (7 points → 7 tick labels via `formatLabel`); `tooltipShowsLabelAndValue` (fire `mouseMove` on the chart surface at x≈middle → tooltip text has `formatLabel(points[3].label)` and `formatValue(points[3].value)`); `startsEmptyWithoutPoints` (no crash on `[]`).
- [ ] **Step 3: Run** `npx vitest run src/components/studio/__tests__/StudioLineChart.test.tsx` — FAIL.
- [ ] **Step 4: Implement.**
- [ ] **Step 5: Run** — PASS.
- [ ] **Step 6: `MetricTabs`** — replace `<LineChart values days>` with `<StudioLineChart points={series.map((value, i) => ({ label: days[i], value }))} formatLabel={dayLabel} formatValue={formatCount} />`; delete the SVG `LineChart`, `linePath`, its test cases. Run `npm test` + `npx tsc --noEmit` — green (`StudioAnalytics` still compiles; its `BarChart` stays).
- [ ] **Step 7: Commit** — `feat(studio): Recharts line chart with TikTok-style hover`

### Task 11: Frontend types for the new contract

**Files:**
- Modify: `web/src/lib/api/types.ts` (`CreatorVideoDetail`, `CreatorDailyStats`, `CreatorTotals`, `CreatorVideoStats`, `MeResponse`, `UserProfileResponse`), `web/src/lib/api/users.ts:76-90` (`updateMyProfile` accepts `birthDate`), `web/src/lib/api/interactions.ts:26-50`, `web/src/lib/api/auth.ts:61-80`

**Interfaces:**
- Produces (TS):
  - `export type WatchSource = "FOR_YOU" | "FOLLOWING" | "FRIENDS" | "EXPLORE" | "PROFILE" | "SEARCH" | "MESSAGE" | "DIRECT"`; `export type TrafficSource = WatchSource | "OTHER"`.
  - `CreatorDailyStats.saves: number`; `CreatorTotals.saves: number`; `CreatorVideoStats.saves: number`.
  - `CreatorVideoTotals { views; likes; comments; shares; saves; newFollowers }`; `TrafficSourceRow { source: TrafficSource; views: number }`; `SearchQueryRow { query: string; views: number }`; `AgeGroup = "13-17" | "18-24" | "25-34" | "35-44" | "45-54" | "55+" | "UNKNOWN"`; `CreatorVideoViewers { total; newViewers; previousTotal; previousNewViewers; breakdownAvailable: boolean; returning: number | null; followers: number | null; nonFollowers: number | null; ageGroups: Record<AgeGroup, number> | null }`; `CreatorVideoDetail` gains `saves, newFollowers, current, previous, trafficSources, searchQueries, viewers`.
  - `MeResponse.birthDate?: string | null`; `UserProfileResponse.birthDate: string | null`; `updateMyProfile(patch: { displayName?; bio?; avatarUrl?; birthDate?: string })`.
  - `recordWatch(videoId: string, watchedMs: number, durationMs: number, source: WatchSource, searchQuery?: string)` — body adds `source` and, only when `source === "SEARCH"` and non-empty, `searchQuery`.
  - `completeSignup(input: { signupToken; username; password; birthDate: string })`.

- [ ] **Step 1:** Make the edits; run `npx tsc --noEmit` and fix every caller the compiler names (tests included) with the obvious value (`"DIRECT"` in `recordWatch` callers is temporary — Task 15 sets the real ones).
- [ ] **Step 2:** `npm test` — green.
- [ ] **Step 3: Commit** — `feat(api): video analytics, watch source and birthDate types`

### Task 12: Studio page — tiles, Saves, previous window, retention on Recharts + insight

**Files:**
- Modify: `web/src/components/studio/StudioVideoAnalytics.tsx` (overview, engagement, `RetentionChart`), `web/src/lib/studio/metrics.ts`, `web/src/lib/studio/__tests__/metrics.test.ts`, `web/src/components/studio/__tests__/StudioVideoAnalytics.test.tsx`

**Interfaces:**
- Produces: `retentionInsight(points: number[]): number | null` in `metrics.ts` — index `i ≥ 1` with the largest `points[i-1] − points[i]`; null when `points.length < 2`, when every drop is 0, or when the input is all zeros. The component renders `Most viewers stopped watching at {formatDuration(durationSeconds * i / 20)}` when `i > 1`, `Most viewers left in the first few seconds` when `i === 1`, nothing when null or `views === 0`.
- Consumes: Task 10's `StudioLineChart`, Task 11's types.

- [ ] **Step 1: Failing `metrics.test.ts`**: `retentionInsightFindsTheLargestDrop` (`[1, .9, .85, .4, .38]` → 3); `retentionInsightIsNullWithoutADrop` (`[1,1,1]`, `[]`, `[0,0]` → null); `retentionInsightPrefersTheFirstStepOnATie`.
- [ ] **Step 2: Failing component tests** (extend the file's mock of `getCreatorVideo` with the full Task 11 shape): `showsFiveOverviewTiles` (text "New followers" with `formatCount(newFollowers)`); `engagementHasSavesWithItsChange` ("Saves", and `changeLine(current.saves, previous.saves)` text); `insightNamesTheSecond` (duration 30, retention dropping most at index 5 → "0:07"); `noInsightWithoutViews` (views 0 → text absent).
- [ ] **Step 3: Run** both files — FAIL.
- [ ] **Step 4: Implement**: tiles array gains `["New followers", data && formatCount(data.newFollowers)]` and the grid becomes `grid-cols-5`; `MetricTabs` metrics add `saves` and set `previous` from `data.previous`, values from `data.current` (drop the local `sum`); `RetentionChart` becomes `<StudioLineChart points={retention.map((v, i) => ({ label: String(i), value: v }))} yDomain={[0, 1]} formatLabel={(_, i) => durationSeconds ? formatDuration(durationSeconds * i / 20) : `${i * 5}%`} formatValue={(v) => `${Math.round(v * 100)}% still watching`} />` with the insight line under it.
- [ ] **Step 5: Run** `npm test` — PASS.
- [ ] **Step 6: Commit** — `feat(studio): new followers, saves, previous window, retention insight`

### Task 13: Studio page — traffic sources and search queries

**Files:**
- Create: `web/src/components/studio/video/BarList.tsx`, `web/src/components/studio/video/TrafficSources.tsx`, `web/src/components/studio/video/SearchQueries.tsx`
- Modify: `web/src/lib/studio/metrics.ts` (`TRAFFIC_SOURCE_LABELS: Record<TrafficSource, string>`), `web/src/components/studio/StudioVideoAnalytics.tsx`
- Test: `web/src/components/studio/__tests__/BarList.test.tsx`, `…/StudioVideoAnalytics.test.tsx`

**Interfaces:**
- Produces: `BarList({ rows, total }: { rows: { key: string; label: string; value: number }[]; total: number })` — one row per entry: label, a `#0075db` bar sized `value/total`, `Math.round(100 * value / total)%` right-aligned; rows with `value === 0` omitted; `total === 0` renders nothing. `TrafficSources({ rows }: { rows: TrafficSourceRow[] })` → `SectionTitle "Traffic source"` + `BarList` sorted desc using `TRAFFIC_SOURCE_LABELS`; `SearchQueries({ rows }: { rows: SearchQueryRow[] })` → title "Search queries", `BarList` over the sum of its own rows, `EmptyState "No search traffic yet"` when empty.

- [ ] **Step 1: Failing tests**: `BarList.omitsZeroRowsAndRoundsPercentages` (`[{a 75},{b 25},{c 0}]`, total 100 → "75%", "25%", no "c"); `StudioVideoAnalytics.listsTrafficSourcesHighestFirstWithTikTokLabels` ("For You" before "Search"; "Personal profile" for PROFILE); `showsTheEmptyStateWithoutSearchTraffic`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** — `feat(studio): traffic sources and search queries`

### Task 14: Studio page — viewers section

**Files:**
- Create: `web/src/components/studio/video/ViewerBreakdown.tsx`
- Modify: `web/src/components/studio/StudioVideoAnalytics.tsx`, `web/src/lib/studio/metrics.ts` (`AGE_GROUPS: AgeGroup[]` in display order, `UNKNOWN` labelled "Unknown")
- Test: `…/StudioVideoAnalytics.test.tsx`

**Interfaces:**
- Produces: `ViewerBreakdown({ viewers }: { viewers: CreatorVideoViewers })` — `MetricTabs` with `Total viewers` (`total`/`previousTotal`) and `New viewers` (`newViewers`/`previousNewViewers`), `days={[]}`; then, when `breakdownAvailable`, three `StudioCard`s titled *Viewer type* (New / Returning), *Followers* (Followers / Non-followers), *Age* (seven groups) each a `BarList` with `total = viewers.total`; otherwise one card with the copy `You'll be able to see this once you have enough viewers to analyze.`

- [ ] **Step 1: Failing tests**: `viewersBreakdownIsWithheldBelowTheThreshold` (copy present, no "Returning"); `viewersBreakdownShowsAllSevenAgeGroupsInOrder`; `newAndReturningSplitTheTotal` ("New" 50%, "Returning" 50% for 900/900 of 1800).
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test && npx tsc --noEmit && npm run lint` — green.
- [ ] **Step 5: Commit** — `feat(studio): per-video viewers`

### Task 15: Watch source from every surface

**Files:**
- Create: `web/src/lib/watch-source.ts`, `web/src/lib/__tests__/watch-source.test.ts`
- Modify: `web/src/hooks/use-watch-session.ts`, `web/src/components/feed/VideoCard.tsx:24-90,185-191`, `web/src/components/feed/list/FeedVideoCard.tsx:116-126`, `web/src/components/feed/Feed.tsx:41-52`, `web/src/components/feed/LiveFeed.tsx:52`, `web/src/components/following/FollowFeed.tsx`, `web/src/components/video/BackendVideoDetail.tsx`, `web/src/components/video/VideoDetail.tsx:70-85,175-182,346-355`, `web/src/components/search/SearchResults.tsx:278,407`, `web/src/components/profile/ProfileBody.tsx:391`, `web/src/components/explore/ExploreGrid.tsx:260`, `web/src/components/messages/thread/VideoMessage.tsx:58`
- Test: `web/src/hooks/__tests__/use-watch-session.test.ts` (create if absent)

**Interfaces:**
- Produces:
  - `watchSourceFromParams(src: string | null): WatchSource` — `search→SEARCH, profile→PROFILE, explore→EXPLORE, message→MESSAGE`, anything else → `DIRECT`; `videoHref(id: string, src: "search" | "profile" | "explore" | "message", q?: string): string` → `/video/{encoded id}?src=…[&q=…]`.
  - `useWatchSession({ …, source: WatchSource, searchQuery?: string })`; `VideoCard` props `source: WatchSource; searchQuery?: string`; `Feed({ videos, onReachEnd, source })`; `VideoDetail({ …, source, searchQuery })`.
  - `BackendVideoDetail` reads `useSearchParams()` once: `source = watchSourceFromParams(params.get("src"))`, `searchQuery = source === "SEARCH" ? params.get("q") ?? undefined : undefined`; `step()` replaces with `` `/video/${id}${window.location.search}` ``.
  - `LiveFeed` → `"FOR_YOU"`; `FollowFeed` → `source === "friends" ? "FRIENDS" : "FOLLOWING"`.

- [ ] **Step 1: Failing tests**: `watch-source.test.ts` — `mapsKnownSrcValues`, `unknownOrMissingIsDirect` (Review Focus 3), `videoHrefEncodesTheQuery`; `use-watch-session.test.ts` — `flushSendsTheSource` (mock `recordWatch`, flush → called with `(…, "SEARCH", "tiny house")`), `queryIsOnlySentForSearch`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** the thread; the five links use `videoHref` (search: `videoHref(video.id, "search", term)` — `term` is already in `SearchResults` scope).
- [ ] **Step 4: Run** `npm test && npx tsc --noEmit` — PASS.
- [ ] **Step 5: Commit** — `feat(watch): report where a view came from`

### Task 16: Follow carries the video

**Files:**
- Modify: `web/src/lib/api/users.ts:207-215` (`follow(userId, sourceVideoId?)`), `web/src/hooks/use-follow.ts:39-127`, `web/src/components/feed/ActionRail.tsx:78-82`
- Test: `web/src/hooks/__tests__/use-follow.test.ts` (create or extend)

**Interfaces:**
- Produces: `follow(userId: string, sourceVideoId?: string)` — body `{ sourceVideoId }` only when given; `useFollow(authorId: string, initialFollowing?: boolean, options?: { sourceVideoId?: string })`; `ActionRail` calls `useFollow(video.author.userId, video.isFollowing, { sourceVideoId: video.id })`.

- [ ] **Step 1: Failing test** `toggleSendsTheSourceVideo` (mock `apiFetch`; toggle → POST body `{ sourceVideoId: "42" }`); `profileFollowSendsNoBody`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS.
- [ ] **Step 5: Commit** — `feat(follow): attribute feed follows to the video`

### Task 17: Birthday — sent at sign-up, asked once afterwards

**Files:**
- Create: `web/src/lib/forms/birthday.ts` (`toIsoBirthDate(month: string, day: string, year: string): string` using `MONTHS` from `schemas.ts`), `web/src/lib/forms/__tests__/birthday.test.ts`
- Create: `web/src/components/session/auth-modal/BirthdayFields.tsx` (the three selects + error row, lifted out of `SignupDetailsStep`), `web/src/components/session/BirthdayPrompt.tsx`
- Modify: `web/src/components/session/auth-modal/SignupSteps.tsx:74-170` (use `BirthdayFields`), `web/src/components/session/AuthModal.tsx:255-262` (`createAccount` passes `birthDate: toIsoBirthDate(draft.birthMonth, draft.birthDay, draft.birthYear)`), `web/src/components/session/SessionProvider.tsx:40-60,147-160` (`SessionUser.birthDate: string | null`; render `<BirthdayPrompt />`; expose `reloadUser: () => Promise<void>` on the context if `loadMe` is not already exposed)
- Test: `web/src/components/session/__tests__/BirthdayPrompt.test.tsx`

**Interfaces:**
- Produces: `BirthdayPrompt()` — reads `useSession()`; renders a `Modal` titled *When's your birthday?* with `BirthdayFields` + a `PillButton` "Continue" when `user && user.profileReady && user.birthDate === null && !dismissed` (session-local `useState`); submit validates with `checkBirthday`, calls `updateMyProfile({ birthDate })`, then `reloadUser()`; closing sets `dismissed`.

- [ ] **Step 1: Failing tests**: `birthday.test.ts` — `toIsoBirthDateZeroPads` (`"May","9","2001"` → `"2001-05-09"`); `BirthdayPrompt.test.tsx` — `asksWhenTheProfileHasNoBirthDate`, `staysQuietWhenItHasOne`, `submitsIsoAndReloads` (mock `updateMyProfile`, expect `{ birthDate: "2001-05-09" }`), `dismissStaysDismissedThisSession`.
- [ ] **Step 2: Run** — FAIL.
- [ ] **Step 3: Implement** (`toSessionUser` maps `me.birthDate ?? null`).
- [ ] **Step 4: Run** `npm test && npx tsc --noEmit && npm run lint` — green.
- [ ] **Step 5: Commit** — `feat(session): birth date at sign-up and a one-time prompt`

### Task 18: End-to-end check and rollout notes

**Files:**
- Modify: `docs/operations.md` (seed import + backfill order), this plan (tick boxes)

- [ ] **Step 1:** Bring the stack up (`make infra-up`, run auth/user/interaction/analytics/gateway and `web`), sign up a fresh account with a birthday, watch a Studio-owned video from `/`, from `/search?q=…` and from a profile, follow its creator from the rail, save it.
- [ ] **Step 2:** Verify in ClickHouse: `SELECT source, search_query FROM watch_events FINAL ORDER BY occurred_at DESC LIMIT 3` shows `FOR_YOU`, `SEARCH`+query, `PROFILE`; `SELECT source_video_id FROM follow_events FINAL ORDER BY occurred_at DESC LIMIT 1` is the video; `SELECT birth_date FROM viewer_profiles FINAL` has the new account.
- [ ] **Step 3:** Open `/studio/analytics/{videoId}` as the creator: five tiles, Saves cell, dashed cursor + tooltip on both charts, insight line, traffic list, the threshold copy under Viewers (test accounts < 100). Lower `analytics.creator.min-audience` to 1 locally and confirm the three breakdowns render.
- [ ] **Step 4:** `POST /api/v1/users/internal/follow-backfill` with the internal token; `SELECT count() FROM follow_events FINAL` grows by the live edge count; no new rows in notification-service's inbox.
- [ ] **Step 5:** Commit docs — `docs: video analytics rollout notes`
