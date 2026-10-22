# Architecture Overview — TikTok Backend

## 1. Request Flow (Happy Path)

```
Client (Mobile/Web)
    │
    ▼
API Gateway :8080          ← Rate limit, JWT verify, Route
    │
    ├──► auth-service :8081     ← /auth/** (không cần JWT)
    ├──► user-service :8082     ← /users/**
    ├──► video-service :8083    ← /videos/**
    └──► ...
```

## 2. Hai Luồng Quan Trọng

### 2a. Upload Video → Feed Fan-out

```
Client → video-service (lưu metadata MongoDB)
    → Kafka: VideoPublishedEvent
        ├→ media-worker (transcode, thumbnail → MinIO)
        ├→ video-service consumer (cập nhật status = READY)
        ├→ recommendation-service (index video)
        └→ Feed Fan-out consumer (ghi vào Redis/Cassandra feed của followers)
```

### 2b. Chat Realtime

```
Client A ──WebSocket──► chat-service (instance 1)
Client B ──WebSocket──► chat-service (instance 2)

A gửi msg → instance 1 → lưu MongoDB
                        → publish Redis Pub/Sub "chat:{conversationId}"
                              → instance 2 subscribe → push xuống Client B
```

## 3. Patterns áp dụng

| Pattern                  | Dùng ở                                            | Mục đích                                           |
|--------------------------|---------------------------------------------------|----------------------------------------------------|
| **Outbox**               | auth, admin, video                                | Đảm bảo event publish không mất khi crash          |
| **CQRS**                 | video-service, interaction-service                | Tách read model (Redis/Cassandra) khỏi write model |
| **Idempotent Consumer**  | Mọi Kafka consumer                                | Chống xử lý trùng (inbox_events table)             |
| **Soft Delete**          | Tất cả services                                   | `deleted_at` thay vì xoá thật                      |
| **Optimistic Lock**      | `BaseEntity` (`@Version`)                         | Chống race condition trên update                   |
| **Dead Letter Queue**    | Mọi service có `@KafkaListener` (qua `kafka-lib`) | Dependency chết → retry tới khi sống lại; lỗi do chính record → 3 lần → `<topic>.DLT` thay vì kẹt consumer vô hạn. Xem §5b |

## 4. Database per Service

| Service                          | DB            | Notes                      |
|----------------------------------|---------------|----------------------------|
| auth, user, admin                | PostgreSQL    | JPA + Flyway               |
| video, story, chat, notification | MongoDB       | `@Document`                |
| interaction                      | Cassandra     | write-heavy (like/comment) |
| search                           | Elasticsearch | sync từ Kafka              |
| analytics                        | ClickHouse    | OLAP                       |

Redis dùng chung, và không chỉ là cache: nó giữ revocation token (`auth:blacklist:*`), bộ đếm OTP/login,
và các set ẩn video của recommendation-service (`reco:video:taken-down`/`private`/`held`). Vì vậy
`maxmemory-policy` **phải là `noeviction`** ở mọi môi trường — policy LRU nào (kể cả `volatile-lru`,
vì revocation key và bộ đếm đều có TTL) cũng có thể lặng lẽ xoá chúng: token đã logout dùng lại được,
giới hạn đoán OTP reset, video bị gỡ quay lại feed. Đầy bộ nhớ thì Redis từ chối ghi và lỗi hiện ra,
thay vì mất dữ liệu âm thầm. Thứ tăng không có TTL là các hash/zset theo video của recommendation
(`reco:video:published`, `owner`, `watches`, `completions`) — theo dõi `used_memory` theo đó.

## 5. Shared Libraries (`libs/`)

```
common-lib       ← Mọi service đều phụ thuộc
  ├── BaseEntity          (id Snowflake, createdAt, updatedAt, deletedAt, version)
  ├── ApiResponse<T>      (success, code, message, data, timestamp)
  ├── DomainException     (sealed — mọi custom exception kế thừa)
  ├── GlobalExceptionHandler
  ├── InternalApiToken    (X-Internal-Token của endpoint nội bộ: so constant-time, fail-fast, bean @Lazy)
  └── SnowflakeIdGenerator

event-schema     ← Services produce/consume Kafka events (mọi record implements DomainEvent: eventId, occurredAt)
  ├── user/        UserRegisteredEvent, SocialAvatarDiscoveredEvent, AvatarMirroredEvent
  ├── video/       VideoPublishedEvent, VideoDeletedEvent, VideoPurgedEvent, VideoTranscodedEvent
  ├── interaction/ VideoLikeEvent, CommentCreatedEvent, CommentDeletedEvent,
  │                VideoSharedEvent, VideoViewedEvent, VideoWatchEvent
  └── admin/       UserBannedEvent, UserUnbannedEvent, VideoTakenDownEvent, VideoRestoredEvent,
                   CommentRemovedEvent

crypto-lib       ← auth-service + api-gateway + admin-service
  ├── JwtProvider         (generate, validate, extract claims)
  ├── HashUtils           (SHA-256, BCrypt wrappers)
  └── AesEncryptor        (PII encryption)

security-lib     ← Centralized JWT auto-configuration (9 services, xem §5a)
  ├── JwtProperties       (JWT secret, expiry, prefix từ environment)
  ├── JwtAuthenticationFilter (Servlet filter validate + extract JWT)
  ├── JwtSecurityAutoConfiguration (Spring Boot auto-config beans)
  │   └── Fail-fast startup: kiểm tra JWT_SECRET tồn tại
  ├── ActuatorSecurityAutoConfiguration (chain riêng cho /actuator: health/info/prometheus không cần token, còn lại denyAll)
  ├── InternalCallerAuthorization (`.access(...)` cho endpoint nội bộ: chỉ request mang X-Internal-Token hợp lệ)
  └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      └── Auto-register bean cho 9 services

kafka-lib        ← Centralized Kafka consumer error handling + outbox dispatch
  ├── KafkaConsumerAutoConfiguration (DefaultErrorHandler + DeadLetterPublishingRecoverer)
  │   └── Dependency chết (DB/Redis/ES/Cassandra/broker không tới được): retry KHÔNG giới hạn, backoff mũ 1s→30s
  │   └── Lỗi do chính record (JSON hỏng, bug): retry 3 lần (FixedBackOff 1s) → topic `<topic>.DLT`
  │   └── Mỗi record vào DLT: log ERROR + counter `kafka_consumer_dead_letters_total{topic}`
  │   └── @ConditionalOnMissingBean — service tự khai bean riêng vẫn override được
  ├── OutboxDispatcher (+ KafkaOutboxAutoConfiguration, OutboxProperties)
  │   └── Chỉ markPublished SAU khi broker ack; send lỗi → để nguyên cho poll sau
  │   └── Gửi cả batch rồi mới chờ ack (pipeline), timeout `tiktok.kafka.outbox.ack-timeout` (30s)
  ├── OutboxBacklog (MeterBinder — mỗi outbox một bean trong `config/OutboxMetricsConfig` của service)
  │   └── `outbox_pending_events{outbox}` + `outbox_oldest_pending_age_seconds{outbox}`, query lại mỗi lần scrape
  │   └── outbox: `outbox_events` (auth, admin, user); video có 5 slot: published, deleted, visibility, purge, purge-request
  ├── DltReplay (`make dlt-list` / `make dlt-replay`: gửi lại record DLT về topic gốc, có kiểm tra thứ tự theo key — §5b)
  └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      └── Auto-register bean khi service thêm dependency, không cần @Configuration cục bộ

moderation-lib   ← Text người xem đọc được: video-service (caption), interaction-service (comment), user-service (từ khoá của creator), admin-service (term + preview)
  ├── TextSanitizer + @MaxCodePoints (NFC, bỏ ký tự vô hình/bidi, đếm độ dài theo code point)
  ├── TextModerator (blocklist tiếng Việt + tín hiệu spam → ALLOW / HOLD / REJECT, in-process)
  │   └── Counter `text_moderation_decisions_total{surface,decision,category,enforced}`
  ├── TextClassifier (gọi moderation-service `POST /moderate/text`, PhoBERT; chỉ HOLD; fail-open + cooldown)
  ├── AdminTermsSync (poll `GET /api/v1/admin/internal/blocklist` mỗi 60s, gộp term admin vào blocklist)
  ├── KeywordFilter (từ khoá comment của creator, khớp như blocklist)
  └── TextModerationAutoConfiguration (`moderation.text.*`; blocklist đọc lúc khởi động, hỏng thì không khởi động)
      Xem docs/moderation.md §Text
```

### 5a. JWT Authentication — security-lib (Centralized)

**9 Services sử dụng security-lib** (auto-configured via Spring Boot):

```
admin-service, analytics-service, chat-service, interaction-service, notification-service,
recommendation-service, story-service, user-service, video-service
```

**2 Services giữ custom JWT config** (exceptions — lý do khác nhau):

- **api-gateway**: WebFlux (không có servlet API) → `JwtConfig` riêng
- **auth-service**: Cấp JWT token (config khác: `auth.jwt.*` prefix, accessTokenExpiryMillis/refreshTokenExpiryMillis) →
  `JwtConfig` riêng

### 5b. kafka-lib — ai đang dùng

**Đang dùng `kafka-lib`** (auto-configured via Spring Boot):

```
auth, user, video, recommendation, media-worker, search,
interaction, analytics, chat, notification                  ← consumer error handling (outage vs poison) → <topic>.DLT
auth-service, admin-service, video-service, user-service    ← OutboxDispatcher (mark sau ack)
```

**Không cần `kafka-lib`** — không có consumer lẫn outbox: `story-service`.

**Chính sách lỗi consumer**: `KafkaConsumerAutoConfiguration.isOutage` đọc cả chuỗi cause. Lỗi dịch
sang `TransientDataAccessException` / `DataAccessResourceFailureException`, không lấy được connection
lúc mở transaction, socket/DNS, hay `RetriableException` của Kafka đều là **dependency chết**: partition
đứng chờ (thấy được qua consumer lag + WARN mỗi lần thử), không mất event. Mọi lỗi khác là của chính
record → DLT sau 3 lần. Client báo "unavailable" theo kiểu khác thì service tự dịch sang transient chỗ
nó bắt lỗi — search-service làm vậy với 429/502/503/504 của Elasticsearch (`ElasticsearchStatus.classify`),
và dịch ngược một câu trả lời có status khác (400, 409…) để nó không bị coi là outage rồi kẹt partition.
`user.privacy-events` ở video-service vẫn retry vô hạn với **mọi** lỗi (`KafkaUnlimitedRetryConfig`).

**Khi có record trong DLT** (log ERROR `parked on <topic>.DLT` / `kafka_consumer_dead_letters_total` tăng):
không có gì tự đọc lại DLT. Sửa nguyên nhân và deploy trước, rồi dùng `kafka-lib/DltReplay`:
`make dlt-list TOPIC=<topic>.DLT` (lý do lỗi, key, eventType, group nào hỏng), sau đó
`make dlt-replay TOPIC=<topic>.DLT OFFSET=<partition:offset>` hoặc `ALL=1` (nhớ vị trí bằng consumer group
`dlt-replay`). Record được gửi lại vào topic gốc với đúng key/value/header (giữ `eventType`, bỏ
`kafka_dlt-*`, thêm `dlt-replayed-from`). Consumer đều idempotent nên gửi trùng an toàn, **nhưng thứ tự
thì không**: tool bỏ qua record nào mà partition gốc đã có record mới hơn cùng key (vd. replay một takedown
sau khi video đã được restore sẽ gỡ nó lần nữa) và in lệnh `FORCE=1` để chạy lại sau khi người đã xem.

### 5c. Kafka — bảng topic đầy đủ

Nguồn sự thật cho phần events trong mọi `docs/*-service-api.md`. Key của mọi topic video/interaction là `videoId` (giữ thứ tự per-video trong partition).

| Topic | Producer | eventType header | Event(s) | Consumers |
|---|---|---|---|---|
| `auth.user-events` | auth-service (outbox) | — (1 shape) | `UserRegisteredEvent` | user-service, chat-service (`AdminFanout`: chỉ báo console admin tải lại, không đọc payload) |
| `auth.social-avatar-events` | auth-service (fire-and-forget; tài khoản vừa tạo: outbox, ngay sau `UserRegisteredEvent`) | — | `SocialAvatarDiscoveredEvent` | media-worker |
| `auth.username-events` | auth-service (outbox, key `userId`) | — (1 shape) | `UsernameChangedEvent` | user-service (bản sao `username` của profile) |
| `video.video-events` | video-service (outbox per-doc) | `VideoPublishedEvent` / `VideoDeletedEvent` / `VideoPurgedEvent` / `VideoVisibilityChangedEvent` (vắng ⇒ Published) | `VideoPublishedEvent`, `VideoDeletedEvent` (ngay khi xoá — gỡ khỏi index), `VideoPurgedEvent` (hết hạn thùng rác — chỉ media-worker xoá media), `VideoVisibilityChangedEvent` | media-worker, search-service, recommendation-service, analytics-service, chat-service |
| `media.video-transcoded-events` | media-worker (chờ ack 30s) | — | `VideoTranscodedEvent` | video-service, search-service, recommendation-service |
| `media.video-moderation-events` | media-worker (chờ ack) | — (1 shape) | `VideoModerationCompletedEvent` (verdict của classifier; lỗi classifier vẫn phát REVIEW) | video-service, search-service, recommendation-service, chat-service (`VideoStateFanout`, `AdminFanout`) |
| `media.avatar-events` | media-worker (chờ ack 30s) | — | `AvatarMirroredEvent` | user-service |
| `user.follow-events` | user-service (outbox) | — (1 shape) | `UserFollowChangedEvent` (+ `requestApproved` khi là lời mời vừa được duyệt) | notification-service, chat-service |
| `user.follow-request-events` | user-service (outbox) | — (1 shape) | `FollowRequestedEvent` | notification-service |
| `user.privacy-events` | user-service (outbox) | — (1 shape) | `UserPrivacyChangedEvent` | video-service |
| `interaction.like-events` | interaction-service (chờ ack 5s) | — | `VideoLikeEvent` | video-service, recommendation-service, search-service |
| `interaction.comment-events` | interaction-service (chờ ack 5s) | `CommentCreatedEvent` / `CommentDeletedEvent` (vắng ⇒ Created) | `CommentCreatedEvent`, `CommentDeletedEvent` | video-service, recommendation-service, search-service |
| `interaction.share-events` | interaction-service (chờ ack 5s) | — | `VideoSharedEvent` | recommendation-service, search-service |
| `interaction.view-events` | interaction-service (chờ ack 5s) | — | `VideoViewedEvent` | video-service, search-service |
| `interaction.watch-events` | interaction-service (fire-and-forget) | — | `VideoWatchEvent` | recommendation-service, analytics-service |
| `interaction.comment-like-events` | interaction-service (fire-and-forget, log nếu broker từ chối) | — | `CommentLikeChangedEvent` | notification-service, chat-service (`VideoStatsFanout`) |
| `interaction.repost-events` | interaction-service (chờ ack) | — | `VideoRepostedEvent` | (chưa có consumer) |
| `interaction.save-events` | interaction-service (chờ ack) | — | `VideoSavedEvent` | chat-service (`VideoStatsFanout`) |
| `video.like-owner-events` | video-service (fire-and-forget, key `ownerId`) | — | `VideoLikeOwnerEvent` | chat-service (`UserStatsFanout`) |
| `notification.created` | notification-service (chỉ announce, không outbox) | — | `NotificationCreatedEvent` | chat-service (`NotificationFanout`: đẩy badge xuống socket) |
| `admin.moderation-events` | admin-service (outbox) | `VideoTakenDownEvent` / `VideoRestoredEvent` / `UserBannedEvent` / `CommentRemovedEvent` / … | mixed (route theo header, bắt buộc) | video-service (`VideoTakenDownEvent` + `VideoRestoredEvent` + `VideoPurgeRequestedEvent` + `UserBannedEvent`/`UserUnbannedEvent` → `owner_bans`), search-service (`VideoTakenDownEvent` + `VideoRestoredEvent` + `UserBannedEvent`/`UserUnbannedEvent` → index `owner_bans`), recommendation-service (`VideoTakenDownEvent` + `VideoRestoredEvent`), auth-service (`UserBannedEvent` + `UserUnbannedEvent` + `UserSessionsRevokedEvent`), interaction-service (`CommentRemovedEvent` + `UserBannedEvent`/`UserUnbannedEvent` → `user_bans`), notification-service (`UserBannedEvent`, `VideoTakenDownEvent`, `UserWarnedEvent`), media-worker (`VideoTakenDownEvent` + `VideoRestoredEvent`: quarantine media), chat-service (mọi event) |
| `admin.report-events` | admin-service (outbox) | `ReportSubmittedEvent` / `ReportResolvedEvent` / `StaleReportsDismissedEvent` | `ReportSubmittedEvent` (có report mới), `ReportResolvedEvent` (mọi report đang chờ của một target vừa được xử lý), `StaleReportsDismissedEvent` (một lô report quá hạn vừa bị `StaleReportSweep` tự đóng) | chat-service (`AdminFanout`, group riêng `admin-realtime-<uuid>` mỗi instance: chỉ báo console admin tải lại, không đọc payload; gộp mỗi loại event còn tối đa một frame trong mỗi cửa sổ `realtime.flush-interval-millis`) |

Ghi chú:
- **Topic trộn nhiều shape** (`video.video-events`, `interaction.comment-events`, `admin.moderation-events`, `admin.report-events`) route bằng Kafka header `eventType`, KHÔNG suy từ JSON. Thiếu route → Jackson vẫn parse sang class sai với field null, không exception. Xem `CLAUDE.md` §Kafka.
- **DLT**: mọi service có consumer đều dùng error handler của `kafka-lib` (§5b). DLT chỉ nhận record lỗi do chính nó; dependency chết thì partition đứng chờ thay vì đẩy event vào DLT. Một topic `<topic>.DLT` gom record của **mọi** consumer group đọc topic đó.
- **Ban**: video-service và search-service tự giữ bản ghi ban theo user (`owner_bans`) và lọc lúc đọc, theo `bannedUntil > now` — vì ban tạm hết hạn do `ExpiredBanSweep` của auth-service gỡ mà **không phát event**. Feed recommendation và trending đi qua `/videos/batch` nên cũng được lọc ở video-service. Comment: interaction-service giữ `user_bans` (Cassandra) và bỏ comment của người đang bị ban khỏi mọi danh sách, counter giữ nguyên.
- **Restore vs verdict**: `VideoRestoredEvent` và verdict đi hai topic khác nhau. search-service và recommendation-service lưu thời điểm admin quyết định (`approvedAt` / `reco:video:approved-at:{id}`); verdict REVIEW/REJECTED sinh **trước** mốc đó được coi là đã duyệt.
- `analytics-service` là consumer của `video.video-events` + `interaction.watch-events` (sink training data cho `rank-service`) — xem `docs/ranking-model.md`. Ngoài phạm vi các doc client.
- Cách feature xếp hạng đi từ các event này tới `rank-service`: `docs/ranking-model.md` §2.

## 6. Ports nhanh

| Service                | Port | DB Port        |
|------------------------|------|----------------|
| api-gateway            | 8080 | —              |
| auth-service           | 8081 | PG:5432        |
| user-service           | 8082 | PG:5433        |
| video-service          | 8083 | Mongo:27017    |
| media-worker           | 8084 | —              |
| interaction-service    | 8085 | Cassandra:9042 |
| story-service          | 8086 | Mongo:27017    |
| recommendation-service | 8087 | —              |
| chat-service           | 8088 | Mongo:27017    |
| notification-service   | 8089 | Mongo:27017    |
| search-service         | 8095 | ES:9200        |
| admin-service          | 8096 | PG:5439        |
| analytics-service      | 8097 | ClickHouse:8123 |
| rank-service (Python)  | 8098 | — (mạng nội bộ, không route qua gateway) |
| moderation-service (Python) | 8099 | — (mạng nội bộ, không route qua gateway) |

Actuator: mọi service Java mở `/actuator/health` (kèm `/health/liveness`, `/health/readiness`),
`/actuator/info`, `/actuator/prometheus` **không cần token** trên chính port của nó (mạng nội bộ; gateway
chỉ route `/api/v1/**`). Riêng api-gateway — port public duy nhất — để actuator ở port quản trị
`MANAGEMENT_PORT` (mặc định **8180**), không publish ra ngoài. Cấu hình chung nằm ở
`common-lib/ManagementDefaults` (service vẫn override được trong `application.yml`).

Endpoint nội bộ (`/api/v1/users/internal/**`, `/api/v1/interactions/internal/**`,
`/api/v1/videos/internal/**`, `/api/v1/conversations/internal/**`, `/api/v1/stories/internal/**`,
`/api/v1/admin/internal/**`, `/api/v1/videos/*/policy`) bị gateway `denyAll` **và** chỉ nhận request mang
`X-Internal-Token` = `INTERNAL_API_TOKEN`: gateway không phải ranh giới duy nhất, vì service nào cũng nghe
trên port riêng. Matcher ở `SecurityConfig` KHÔNG mang `HttpMethod`: Spring MVC trả lời HEAD bằng handler
GET, nên rule chỉ cho GET để một HEAD mang JWT của user lọt xuống rule sau. Gateway chỉ gửi token sang
auth-service (nơi duy nhất đọc `X-Client-Ip`), không gửi lên route khác. Khi deploy chỉ publish port
gateway; chạy local, `make run-*` cho mọi service trừ gateway bind `127.0.0.1`
(`make run-user BIND=0.0.0.0` để mở).

## 6b. Vận hành

- **Image**: một `Dockerfile` chung ở root cho mọi service Java: `make image SERVICE=auth-service`
  (= `docker build --build-arg SERVICE=auth-service .`). `.dockerignore` loại `.env` khỏi build context.
- **Metrics đáng canh** (Prometheus, tag `application`): consumer lag
  (`kafka_consumer_fetch_manager_records_lag_max`), `kafka_consumer_dead_letters_total` (record vào DLT —
  mỗi lần tăng là một event chưa được xử lý), `outbox_oldest_pending_age_seconds{outbox}` (event đã commit
  mà chưa tới Kafka — poll chạy 5s/lần nên tuổi vượt vài phút là poller kẹt chứ không phải tải cao; video
  đếm cả bản ghi bị `eventFailedAt` gác lại, nên alert còn bật tới khi có người gỡ cờ), HTTP latency/5xx
  (`http_server_requests_seconds_*`).
- **Alert tối thiểu** (repo chưa chạy Prometheus; dán vào `rule_files`). Nhiều replica cùng đọc một DB
  nên lấy `max`, không cộng:
  ```yaml
  groups:
    - name: tiktok-events
      rules:
        - alert: OutboxStuck
          expr: max by (application, outbox) (outbox_oldest_pending_age_seconds) > 120
          for: 5m
        - alert: EventDeadLettered
          expr: sum by (application, topic) (increase(kafka_consumer_dead_letters_total[10m])) > 0
  ```
- **`INTERNAL_API_TOKEN`** (≥ 32 byte, **cùng một giá trị**): bắt buộc ở api-gateway, auth, user, video,
  interaction, chat, notification, recommendation, search; thiếu thì service không khởi động (ngoài profile
  local/test). Lệch giá trị giữa các service → mọi call nội bộ 403. Đổi token: (1) mọi service thêm
  `INTERNAL_API_TOKEN_ROTATING=<mới>`, (2) đổi chỗ hai giá trị ở mọi service, (3) bỏ `..._ROTATING`.
- **Elasticsearch 9**: client của Spring Data Elasticsearch 6 chỉ nói chuyện với server 9, mà ES 9 không khởi
  động trên data của bản 8 cũ hơn 8.19. Volume đang chạy 8.x phải qua `elasticsearch:8.19.x` một lần (đổi image,
  `docker compose up -d elasticsearch`, chờ green/yellow) rồi mới lên 9.4.5. Xoá volume `elasticsearch-data` cũng
  chạy được, nhưng index trống: không có job reindex từ nguồn, chỉ event mới mới vào lại.
- **Chạy nhiều replica**: đặt `SNOWFLAKE_NODE_ID` (0–1023) khác nhau cho từng instance. Để trống thì mỗi
  instance tự chọn ngẫu nhiên — ổn với 1 replica, nhưng 2 replica có 1/1024 khả năng trùng node id, và
  Cassandra ghi đè comment trùng id mà không báo.

## 7. Tài liệu theo service

| Tài liệu | Đối tượng | Nội dung |
|---|---|---|
| [`api-gateway.md`](api-gateway.md) | client + backend | Bảng route, path công khai, `GET /api/v1/me` gộp, rate limit theo IP (admin theo tài khoản) |
| [`auth-service-api.md`](auth-service-api.md) | client (Flutter) | Đăng ký / đăng nhập / refresh rotation / OTP / social login + §9 events |
| [`user-service-api.md`](user-service-api.md) | client | Profile, avatar upload, follow/block/mute + §8 events |
| [`video-service-api.md`](video-service-api.md) | client | Upload URL, publish, feed cursor, feed/following, batch, delete + §8 events |
| [`interaction-service-api.md`](interaction-service-api.md) | client | Like / comment / share / view / watch + §7 events |
| [`recommendation-service-api.md`](recommendation-service-api.md) | client | `/trending`, `/feed` (chỉ trả id) + §8 events |
| [`search-service-api.md`](search-service-api.md) | client | Tìm video (q / hashtag) + §7 events |
| [`media-worker.md`](media-worker.md) | vận hành | Transcode (copy, chưa ffmpeg), cleanup MinIO, mirror avatar |
| [`ranking-model.md`](ranking-model.md) | vận hành | Huấn luyện + phục vụ `rank-service`, hợp đồng feature |

Chưa có doc riêng: `admin-service`, `analytics-service`, `story/chat/notification` (chưa expose qua gateway).
