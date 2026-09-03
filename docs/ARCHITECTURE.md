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
    ├──► order-service :8092    ← /orders/**
    └──► ...
```

## 2. Ba Luồng Quan Trọng

### 2a. Upload Video → Feed Fan-out

```
Client → video-service (lưu metadata MongoDB)
    → Kafka: VideoPublishedEvent
        ├→ media-worker (transcode, thumbnail → MinIO)
        ├→ video-service consumer (cập nhật status = READY)
        ├→ recommendation-service (index video)
        └→ Feed Fan-out consumer (ghi vào Redis/Cassandra feed của followers)
```

### 2b. Đặt Hàng — Saga (Order → Inventory → Payment)

```
Client → order-service
    1. Tạo Order (status=PENDING) + ghi outbox_events [1 transaction]
    2. Outbox Relay → Kafka → inventory-service
         ✅ ReserveOK → Kafka → payment-service
              ✅ PayOK → Kafka → order-service (CONFIRMED) → notification-service
              ❌ PayFail → Kafka → inventory-service (release) → order (CANCELLED)
         ❌ ReserveFail → order (CANCELLED) → notification-service
```

### 2c. Chat Realtime

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
| **Outbox**               | order, payment, inventory, video                  | Đảm bảo event publish không mất khi crash          |
| **Saga (Orchestration)** | order-service                                     | Distributed transaction: order→inventory→payment   |
| **CQRS**                 | video-service, order-service, interaction-service | Tách read model (Redis/Cassandra) khỏi write model |
| **Idempotent Consumer**  | Mọi Kafka consumer                                | Chống xử lý trùng (inbox_events table)             |
| **Soft Delete**          | Tất cả services                                   | `deleted_at` thay vì xoá thật                      |
| **Optimistic Lock**      | inventory, order, payment                         | `@Version` chống race condition                    |
| **Dead Letter Queue**    | user-service, video-service (qua `kafka-lib`)     | Poison message retry 3 lần → `<topic>.DLT` thay vì kẹt consumer vô hạn |

## 4. Database per Service

| Service                                                     | DB            | Notes                          |
|-------------------------------------------------------------|---------------|--------------------------------|
| auth, user, product, cart, order, payment, inventory, admin | PostgreSQL    | JPA + Flyway                   |
| video, story, chat, notification                            | MongoDB       | `@Document`                    |
| interaction                                                 | Cassandra     | write-heavy (like/comment)     |
| search                                                      | Elasticsearch | sync từ Kafka                  |
| analytics                                                   | ClickHouse    | OLAP                           |
| cart (primary)                                              | Redis         | backup định kỳ sang PostgreSQL |

## 5. Shared Libraries (`libs/`)

```
common-lib       ← Mọi service đều phụ thuộc
  ├── BaseEntity          (id Snowflake, createdAt, updatedAt, deletedAt, version)
  ├── ApiResponse<T>      (success, code, message, data, timestamp)
  ├── DomainException     (sealed — mọi custom exception kế thừa)
  ├── GlobalExceptionHandler
  └── SnowflakeIdGenerator

event-schema     ← Services produce/consume Kafka events (mọi record implements DomainEvent: eventId, occurredAt)
  ├── user/        UserRegisteredEvent, SocialAvatarDiscoveredEvent, AvatarMirroredEvent
  ├── video/       VideoPublishedEvent, VideoDeletedEvent, VideoTranscodedEvent
  ├── interaction/ VideoLikeEvent, CommentCreatedEvent, CommentDeletedEvent,
  │                VideoSharedEvent, VideoViewedEvent, VideoWatchEvent
  ├── admin/       UserBannedEvent, UserUnbannedEvent, VideoTakenDownEvent, VideoRestoredEvent,
  │                ProductSuspendedEvent, ProductReactivatedEvent
  ├── product/     ProductCreatedEvent
  ├── order/       OrderCreatedEvent, OrderConfirmedEvent, OrderCancelledEvent, OrderItem
  ├── payment/     PaymentCompletedEvent, PaymentFailedEvent
  └── inventory/   InventoryReservedEvent, InventoryReleasedEvent, InventoryReservationFailedEvent

crypto-lib       ← auth-service + api-gateway + admin-service
  ├── JwtProvider         (generate, validate, extract claims)
  ├── HashUtils           (SHA-256, BCrypt wrappers)
  └── AesEncryptor        (PII encryption)

security-lib     ← Centralized JWT auto-configuration (13 services)
  ├── JwtProperties       (JWT secret, expiry, prefix từ environment)
  ├── JwtAuthenticationFilter (Servlet filter validate + extract JWT)
  ├── JwtSecurityAutoConfiguration (Spring Boot auto-config beans)
  │   └── Fail-fast startup: kiểm tra JWT_SECRET tồn tại
  └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      └── Auto-register bean cho 13 services

kafka-lib        ← Centralized Kafka consumer error handling + outbox dispatch
  ├── KafkaConsumerAutoConfiguration (DefaultErrorHandler + DeadLetterPublishingRecoverer)
  │   └── Poison message: retry 3 lần (FixedBackOff 1s) → topic `<topic>.DLT`
  │   └── @ConditionalOnMissingBean — service tự khai bean riêng vẫn override được
  ├── OutboxDispatcher (+ KafkaOutboxAutoConfiguration, OutboxProperties)
  │   └── Chỉ markPublished SAU khi broker ack; send lỗi → để nguyên cho poll sau
  │   └── Gửi cả batch rồi mới chờ ack (pipeline), timeout `tiktok.kafka.outbox.ack-timeout` (30s)
  └── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      └── Auto-register bean khi service thêm dependency, không cần @Configuration cục bộ
```

### 5a. JWT Authentication — security-lib (Centralized)

**13 Services sử dụng security-lib** (auto-configured via Spring Boot):

```
admin-service, cart-service, chat-service, interaction-service,
inventory-service, notification-service, order-service, payment-service,
product-service, recommendation-service, story-service, user-service, video-service
```

**2 Services giữ custom JWT config** (exceptions — lý do khác nhau):

- **api-gateway**: WebFlux (không có servlet API) → `JwtConfig` riêng
- **auth-service**: Cấp JWT token (config khác: `auth.jwt.*` prefix, accessTokenExpiryMillis/refreshTokenExpiryMillis) →
  `JwtConfig` riêng

### 5b. kafka-lib — ai đang dùng

**Đang dùng `kafka-lib`** (auto-configured via Spring Boot):

```
user-service, video-service, recommendation-service, media-worker  ← consumer error handling → <topic>.DLT
auth-service, admin-service, video-service                          ← OutboxDispatcher (mark sau ack)
```

**Chưa migrate consumer error handling** — có `@KafkaListener` nhưng vẫn dùng default
retry-vô-hạn của Spring Kafka (thêm dependency `kafka-lib` khi cần):

```
analytics-service, inventory-service, notification-service,
order-service, payment-service, search-service
```

**Chưa migrate outbox** — vẫn `markPublished()` ngay sau `send()`, event mất khi broker từ chối.
Các bước migrate: [`docs/outbox-migration.md`](outbox-migration.md) (marker `TODO(outbox)` đặt sẵn
tại chỗ lỗi trong từng file):

```
inventory-service, order-service, payment-service, product-service
```

**Không cần `kafka-lib`** — không có consumer lẫn outbox:

```
interaction-service, story-service
```

### 5c. Kafka — bảng topic đầy đủ

Nguồn sự thật cho phần events trong mọi `docs/*-service-api.md`. Key của mọi topic video/interaction là `videoId` (giữ thứ tự per-video trong partition).

| Topic | Producer | eventType header | Event(s) | Consumers |
|---|---|---|---|---|
| `auth.user-events` | auth-service (outbox) | — (1 shape) | `UserRegisteredEvent` | user-service |
| `auth.social-avatar-events` | auth-service (fire-and-forget) | — | `SocialAvatarDiscoveredEvent` | media-worker |
| `video.video-events` | video-service (outbox per-doc) | `VideoPublishedEvent` / `VideoDeletedEvent` (vắng ⇒ Published) | `VideoPublishedEvent`, `VideoDeletedEvent` | media-worker, search-service, recommendation-service, analytics-service |
| `media.video-transcoded-events` | media-worker (chờ ack 30s) | — | `VideoTranscodedEvent` | video-service, search-service, recommendation-service |
| `media.avatar-events` | media-worker (chờ ack 30s) | — | `AvatarMirroredEvent` | user-service |
| `interaction.like-events` | interaction-service (chờ ack 5s) | — | `VideoLikeEvent` | video-service, recommendation-service, search-service |
| `interaction.comment-events` | interaction-service (chờ ack 5s) | `CommentCreatedEvent` / `CommentDeletedEvent` (vắng ⇒ Created) | `CommentCreatedEvent`, `CommentDeletedEvent` | video-service, recommendation-service, search-service |
| `interaction.share-events` | interaction-service (chờ ack 5s) | — | `VideoSharedEvent` | recommendation-service, search-service |
| `interaction.view-events` | interaction-service (chờ ack 5s) | — | `VideoViewedEvent` | video-service |
| `interaction.watch-events` | interaction-service (fire-and-forget) | — | `VideoWatchEvent` | recommendation-service, analytics-service |
| `admin.moderation-events` | admin-service (outbox) | `VideoTakenDownEvent` / `VideoRestoredEvent` / `UserBannedEvent` / … | mixed (route theo header, bắt buộc) | video-service (chỉ nhận `VideoTakenDownEvent` + `VideoRestoredEvent`) |
| `product.product-events` | product-service | — | `ProductCreatedEvent` | search-service |

Ghi chú:
- **Topic trộn nhiều shape** (`video.video-events`, `interaction.comment-events`, `admin.moderation-events`) route bằng Kafka header `eventType`, KHÔNG suy từ JSON. Thiếu route → Jackson vẫn parse sang class sai với field null, không exception. Xem `CLAUDE.md` §Kafka.
- **DLT**: chỉ service dùng `kafka-lib` error handler (user, video, recommendation, media-worker) mới có `<topic>.DLT`; các service còn lại retry vô hạn theo mặc định Spring Kafka.
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
| product-service        | 8090 | PG:5434        |
| cart-service           | 8091 | PG:5435        |
| order-service          | 8092 | PG:5436        |
| payment-service        | 8093 | PG:5437        |
| inventory-service      | 8094 | PG:5438        |
| search-service         | 8095 | ES:9200        |
| admin-service          | 8096 | PG:5439        |
| analytics-service      | 8097 | ClickHouse:8123 |
| rank-service (Python)  | 8098 | — (mạng nội bộ, không route qua gateway) |

## 7. Tài liệu theo service

| Tài liệu | Đối tượng | Nội dung |
|---|---|---|
| [`api-gateway.md`](api-gateway.md) | client + backend | Bảng route, path công khai, `GET /api/v1/me` gộp, rate limit theo IP |
| [`auth-service-api.md`](auth-service-api.md) | client (Flutter) | Đăng ký / đăng nhập / refresh rotation / OTP / social login + §9 events |
| [`user-service-api.md`](user-service-api.md) | client | Profile, avatar upload, follow/block/mute + §8 events |
| [`video-service-api.md`](video-service-api.md) | client | Upload URL, publish, feed cursor, feed/following, batch, delete + §8 events |
| [`interaction-service-api.md`](interaction-service-api.md) | client | Like / comment / share / view / watch + §7 events |
| [`recommendation-service-api.md`](recommendation-service-api.md) | client | `/trending`, `/feed` (chỉ trả id) + §8 events |
| [`search-service-api.md`](search-service-api.md) | client | Tìm video (q / hashtag), tìm sản phẩm + §7 events |
| [`media-worker.md`](media-worker.md) | vận hành | Transcode (copy, chưa ffmpeg), cleanup MinIO, mirror avatar |
| [`ranking-model.md`](ranking-model.md) | vận hành | Huấn luyện + phục vụ `rank-service`, hợp đồng feature |
| [`outbox-migration.md`](outbox-migration.md) | backend | Các bước migrate `markPublished()` sang `OutboxDispatcher` |

Chưa có doc riêng: `admin-service`, `analytics-service`, `story/chat/notification/product/cart/order/payment/inventory` (chưa expose qua gateway).
