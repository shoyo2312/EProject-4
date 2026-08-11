# Project Context: TikTok Backend — Microservices Monorepo

## 1. Tech Stack
- **Java**: 21 LTS
- **Framework**: Spring Boot 3.3.x | Spring Cloud 2023.0.x
- **Build**: Maven 3.9.x — dùng `./mvnw` (macOS/Linux) hoặc `mvnw.cmd` (Windows)
- **API Gateway**: Spring Cloud Gateway (WebFlux/reactive) — port 8080
- **Databases**: PostgreSQL 16 (JPA + Flyway) | MongoDB 7 (Spring Data Mongo)
- **Cache**: Redis 7 (Spring Data Redis)
- **Messaging**: Kafka (Spring Kafka) — event-driven giữa services
- **Search**: Elasticsearch 8
- **Storage**: MinIO (S3-compatible)
- **Docs**: springdoc-openapi 2.x (`/swagger-ui.html`)

## 2. Monorepo Structure
```
tiktok-backend/
├── libs/
│   ├── common-lib/       # BaseEntity, ApiResponse, exceptions, SnowflakeId
│   ├── event-schema/     # Kafka event POJOs (shared giữa producers/consumers)
│   ├── crypto-lib/       # JwtProvider, HashUtils, AesEncryptor
│   ├── security-lib/     # Centralized JWT config + auto-configuration (JwtProperties, JwtAuthenticationFilter, JwtSecurityAutoConfiguration, RevokedTokenChecker)
│   └── kafka-lib/        # Centralized Kafka consumer error handling + auto-configuration (KafkaConsumerAutoConfiguration → DefaultErrorHandler + DLQ)
└── services/
    ├── api-gateway/      :8080  WebFlux — KHÔNG dùng spring-boot-starter-web
    ├── auth-service/     :8081  PostgreSQL + Security + Flyway
    ├── user-service/     :8082  PostgreSQL + Security + Flyway
    ├── video-service/    :8083  MongoDB
    ├── media-worker/     :8084  MinIO + Kafka consumer
    ├── interaction-service/ :8085  Cassandra + Redis
    ├── story-service/    :8086  MongoDB (TTL 24h)
    ├── recommendation-service/ :8087  Kafka consumer + Redis
    ├── chat-service/     :8088  MongoDB + WebSocket
    ├── notification-service/   :8089  MongoDB + FCM
    ├── product-service/  :8090  PostgreSQL + Flyway
    ├── cart-service/     :8091  Redis primary + PostgreSQL backup
    ├── order-service/    :8092  PostgreSQL + Saga orchestrator + Outbox
    ├── payment-service/  :8093  PostgreSQL + Outbox (immutable ledger)
    ├── inventory-service/ :8094  PostgreSQL + Outbox + optimistic lock
    ├── search-service/   :8095  Elasticsearch
    ├── admin-service/    :8096  PostgreSQL + Security
    └── analytics-service/ :8097  ClickHouse / Kafka consumer
```

## 3. Package Structure (mỗi service)
```
com.tiktok.{service}/
├── controller/     # @RestController — chỉ validate DTO + delegate Service
├── service/        # Interface + Impl — business logic
├── repository/     # Spring Data JPA / Mongo Repository
├── entity/         # @Entity JPA (PostgreSQL) hoặc @Document (MongoDB)
├── dto/
│   ├── request/    # Input DTOs — dùng Java record hoặc Lombok @Value
│   └── response/   # Output DTOs
├── mapper/         # MapStruct @Mapper
├── exception/      # Custom exceptions + @ControllerAdvice
├── config/         # @Configuration beans
└── event/
    ├── producer/   # Kafka producers
    └── consumer/   # Kafka consumers
```

## 4. Coding Conventions — BẮT BUỘC

### Entities
- `@Entity` + `@Table(name="...")` — KHÔNG dùng `@Data` trên entity
- Dùng `@Getter @Builder @NoArgsConstructor @AllArgsConstructor`
- Kế thừa `BaseEntity` từ `common-lib` (có `id`, `createdAt`, `updatedAt`, `deletedAt`, `version`)
- Soft delete: set `deletedAt`, KHÔNG hard delete
- Optimistic lock: `@Version` (đã có trong `BaseEntity`)

### DTOs
- Dùng Java `record` cho immutable DTOs
- Dùng `@Value` (Lombok) nếu cần builder
- Validation: `jakarta.validation` annotations (`@NotBlank`, `@Email`, `@Size`)
- MapStruct `@Mapper(componentModel = "spring")` để convert Entity ↔ DTO

### Dependency Injection
- **LUÔN** dùng Constructor Injection hoặc `@RequiredArgsConstructor`
- **KHÔNG BAO GIỜ** dùng `@Autowired` trên field

### Error Handling
- Throw custom exception kế thừa `DomainException` (trong `common-lib`)
- `GlobalExceptionHandler` (@ControllerAdvice) bắt và trả `ApiResponse<T>`
- Format lỗi chuẩn: `{ "success": false, "code": "USER_NOT_FOUND", "message": "...", "timestamp": "..." }`

### Database
- **PostgreSQL**: JPA entity + Flyway migration (`V{n}__{mô tả}.sql` trong `resources/db/migration/`)
- **MongoDB**: `@Document` + `@Indexed` — KHÔNG dùng JPA
- ID: `Long` Snowflake (từ `common-lib`) — KHÔNG dùng `UUID` làm PK PostgreSQL
- Queries: Spring Data naming convention ưu tiên; `@Query` chỉ khi complex

### Kafka
- Producer: ghi vào `outbox_events` table cùng transaction DB (Outbox pattern)
- **Publish outbox**: chỉ `markPublished()` SAU khi broker ack. `KafkaTemplate.send()` là async — nó chỉ throw đồng bộ khi serialize lỗi hoặc buffer đầy, nên đánh dấu ngay sau lời gọi sẽ đánh dấu cả những row broker chưa hề nhận; query poll bỏ qua row đã đánh dấu → event mất vĩnh viễn, đúng thứ outbox sinh ra để chống. Dùng `OutboxDispatcher` của `kafka-lib`, đừng tự viết lại
- Consumer: **claim** `eventId` TRƯỚC khi xử lý, không phải check-rồi-ghi-sau. Check-then-act để lọt 2 delivery song song (rebalance) cùng qua cửa → `$inc` đếm 2 lần, sai vĩnh viễn
  - PostgreSQL: `INSERT ... ON CONFLICT DO NOTHING` trong transaction (xem `user-service/InboxEventRepository.tryClaim`)
  - MongoDB: insert dựa vào unique index trên `eventId`; không có transaction nên phải release claim khi xử lý lỗi (xem `video-service/IdempotentEventProcessor`)
- Event class lấy từ `libs/event-schema`
- **Topic trộn nhiều event type** (vd. `admin.moderation-events`): payload JSON không có field phân biệt loại — dùng Kafka header `eventType` (đọc qua `@Header(name = "eventType")`) để route, KHÔNG suy đoán từ shape JSON
- **kafka-lib usage**: dependency `<artifactId>kafka-lib</artifactId>`, auto-config qua Spring Boot — không cần `@Configuration` cục bộ. Hai thứ độc lập nhau:
  - `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` cho mọi `@KafkaListener` (retry 3 lần rồi đẩy sang `<topic>.DLT` thay vì kẹt consumer vô hạn) — đang dùng: `user-service`, `video-service`
  - `OutboxDispatcher` (mark sau ack, xem §Publish outbox) — đang dùng: `auth-service`, `admin-service`, `video-service`
  - CÓ `@KafkaListener` nhưng CHƯA migrate error handler (analytics, inventory, media-worker, notification, order, payment, recommendation, search) — vẫn dùng default retry-vô-hạn của Spring Kafka
  - CÓ outbox nhưng CHƯA migrate dispatcher (inventory, order, payment, product) — vẫn `markPublished()` ngay sau `send()`, tức là đang mất event khi broker từ chối. **Khi động vào 1 trong 4 service này, migrate luôn**: các bước trong `docs/outbox-migration.md`, marker `TODO(outbox)` nằm ngay tại chỗ lỗi trong từng `OutboxPublisher`
  - interaction, story không có consumer lẫn outbox — không cần `kafka-lib`

### JWT Authentication & security-lib
- **security-lib usage**: 12 services (admin, cart, chat, interaction, inventory, notification, order, payment, product, story, user, video) dùng centralized `security-lib` để validate JWT token
  - Dependency: `<artifactId>security-lib</artifactId>`
  - Auto-configured via Spring Boot auto-configuration (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)
  - Services KHÔNG cần `@Configuration` cục bộ cho JWT — được inject tự động
  - Fail-fast: kiểm tra JWT_SECRET có tồn tại khi service startup
- **Token type — BẮT BUỘC**: access token và refresh token cùng ký bằng 1 secret, phân biệt bằng claim `tokenType` (`access`/`refresh`). Mọi nơi authenticate từ bearer token PHẢI dùng `JwtProvider.isValidAccessToken()`, KHÔNG dùng `isValid()` — nếu không refresh token (7 ngày) sẽ đăng nhập được như access token
- **Revocation**: hai loại key, đọc bởi 3 read side (api-gateway, auth-service filter, `RevokedTokenChecker` của security-lib). Tên key + phép so mốc cắt nằm ở `crypto-lib/RevocationKeys` — sửa ở đó, KHÔNG viết chuỗi `"auth:blacklist:..."` trong service. Lệnh Redis vẫn để ở từng read side vì gateway reactive, hai bên kia blocking:
  - `auth:blacklist:{jti}` — logout, giết đúng 1 access token
  - `auth:blacklist:user:{userId}` = mốc epoch millis — giết MỌI access token cấp trước mốc đó, dùng khi cả phiên phải chết cùng lúc (reset password, phát hiện refresh token replay). Access token là stateless nên không có danh sách jti để chặn; so sánh bằng `JwtProvider.issuedAtMillis(claims)` (claim `iatMs`, vì `iat` chuẩn chỉ có độ phân giải giây → không phân biệt được token cấp cùng giây với mốc cắt)
  - Tất cả fail-open khi Redis chết; service không có Redis trên classpath nhận bản no-op
- **Refresh rotation**: `/refresh` xoay token bằng `RefreshTokenRepository.claimForRotation()` — một `UPDATE ... WHERE rotated_at IS NULL` set cả `revoked_at` lẫn `rotated_at`, KHÔNG phải đọc-kiểm-tra-rồi-ghi. Số row ảnh hưởng là kết quả kiểm tra: 0 nghĩa là request khác vừa xoay token này. Đọc rồi check `rotatedAt == null` rồi mới ghi thì hai request đồng thời đều thấy null, đều xoay, và hai chain cùng sống — đúng kịch bản attacker + user thật mà replay detection sinh ra để bắt. `revoked_at IS NULL` KHÔNG được nằm trong `WHERE`: logout revoke mà không rotate, tính nhầm thành thua race thì logout ở 1 máy sẽ đá văng mọi máy khác
- **Replay detection**: trình lại token ĐÃ rotate mà chưa hết hạn → `SessionRevoker.revokeAllSessions()` giết toàn bộ phiên của user. Chỉ `rotated_at` mới kích hoạt, KHÔNG phải `revoked_at` (lý do như trên). Ngoại lệ là **grace window** `auth.jwt.rotation-grace-millis` (10s): trong khoảng đó chỉ trả 401, không giết phiên — client retry một request `/refresh` mất response trông y hệt kẻ trộm, khác nhau ở thời điểm. Client thật chỉ refresh khi access token hết hạn (15 phút sau), kẻ trộm còn muộn hơn. Nới grace = nới cửa sổ replay, giữ ở scale của request timeout. `SessionRevoker` chạy `REQUIRES_NEW` vì luồng replay revoke xong rồi throw; chạy chung transaction thì throw sẽ rollback luôn việc revoke
- **Login**: bắt buộc `emailVerified` — chưa verify thì 403 `EMAIL_NOT_VERIFIED` (khác `INVALID_CREDENTIALS` có chủ đích: client cần biết để mở màn hình gửi lại OTP)
- **Exceptions (NOT using security-lib)**:
  - `api-gateway`: dùng WebFlux (không có servlet API) — giữ JwtConfig/JwtProperties riêng
  - `auth-service`: cấp phát JWT token (khác config: accessTokenExpiryMillis/refreshTokenExpiryMillis, prefix `auth.jwt`) — giữ file riêng + đã có fail-fast validation

### Dev-only affordances — PHẢI gỡ trước khi deploy production
Những thứ dưới đây cố ý nằm trong repo để test thủ công (Postman) không cần đọc email thật. Chúng vi phạm rule "KHÔNG lưu sensitive data vào log" ở §6 và chỉ được chấp nhận ở local:

| Cái gì | Ở đâu | Rủi ro nếu lên production |
|---|---|---|
| `log.warn("[DEV ONLY - REMOVE BEFORE COMMIT] ... OTP ...")` | `auth-service/event/local/EmailNotificationListener.java` (2 chỗ: verify email + reset password) | OTP hiện nguyên văn trong log — ai đọc được log là chiếm được tài khoản bất kỳ |

**Trước mỗi lần deploy thật**: `grep -rn "DEV ONLY" services/` phải trả về rỗng.

## 5. Common Commands
```bash
# Build
./mvnw clean install -DskipTests                          # Toàn bộ monorepo
./mvnw clean install -DskipTests -pl libs/common-lib -am  # Chỉ common-lib

# Run service
./mvnw spring-boot:run -pl services/auth-service -Dspring-boot.run.profiles=local

# Test
./mvnw test -pl services/auth-service

# Makefile shortcuts
make build          # Build toàn bộ
make run-auth       # Run auth-service
make infra-up       # Start Docker (PostgreSQL, Redis, Kafka, MongoDB...)
make help           # Xem tất cả lệnh
```

## 6. Key Rules — KHÔNG được vi phạm
- [ ] KHÔNG query trực tiếp DB của service khác — gọi qua HTTP API hoặc Kafka event
- [ ] KHÔNG dùng Float/Double cho tiền — dùng `BigDecimal`
- [ ] KHÔNG hard delete — chỉ soft delete (`deletedAt`)
- [ ] KHÔNG `@Autowired` field injection
- [ ] KHÔNG dùng `@Data` trên `@Entity`
- [ ] KHÔNG lưu sensitive data (password, token thô) vào Redis/log
- [ ] Mọi Kafka consumer PHẢI idempotent (claim eventId trước khi xử lý, xem §Kafka)
- [ ] MongoDB service: BẬT `spring.data.mongodb.auto-index-creation` — mặc định TẮT từ Spring Data Mongo 3.x, `@Indexed`/`@CompoundIndex` sẽ im lặng không được tạo
- [ ] `api-gateway` dùng WebFlux — KHÔNG import `spring-boot-starter-web`

## 7. Khi Claude sinh code — checklist
- [ ] Package đúng convention: `com.tiktok.{service}.{layer}`
- [ ] Entity kế thừa `BaseEntity` từ `common-lib`
- [ ] DTO dùng Java `record` (hoặc `@Value` nếu cần builder)
- [ ] Mapper dùng MapStruct
- [ ] Service dùng interface + impl
- [ ] Constructor injection (`@RequiredArgsConstructor`)
- [ ] Flyway SQL nếu là PostgreSQL service
- [ ] Không có business logic trong Controller hay Entity
