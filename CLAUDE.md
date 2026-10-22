# Project Context: TikTok Backend — Microservices Monorepo

## 1. Tech Stack
- **Java**: 21 LTS
- **Framework**: Spring Boot 4.1.x | Spring Cloud 2025.1.x (Spring Framework 7, Spring Security 7, Jackson 3)
- **Build**: Maven 3.9.x — dùng `./mvnw` (macOS/Linux) hoặc `mvnw.cmd` (Windows)
- **API Gateway**: Spring Cloud Gateway (WebFlux/reactive) — port 8080
- **Databases**: PostgreSQL 16 (JPA + Flyway) | MongoDB 7 (Spring Data Mongo)
- **Cache**: Redis 7 (Spring Data Redis)
- **Messaging**: Kafka (Spring Kafka) — event-driven giữa services
- **Search**: Elasticsearch 9
- **Storage**: MinIO (S3-compatible)
- **Docs**: springdoc-openapi 3.x (`/swagger-ui.html`)

## 2. Monorepo Structure
```
tiktok-backend/
├── libs/
│   ├── common-lib/       # BaseEntity, ApiResponse, exceptions, SnowflakeId
│   ├── event-schema/     # Kafka event POJOs (shared giữa producers/consumers)
│   ├── crypto-lib/       # JwtProvider, HashUtils, AesEncryptor
│   ├── security-lib/     # Centralized JWT config + auto-configuration (JwtProperties, JwtAuthenticationFilter, JwtSecurityAutoConfiguration, RevokedTokenChecker)
│   ├── kafka-lib/        # Centralized Kafka consumer error handling + auto-configuration (KafkaConsumerAutoConfiguration → DefaultErrorHandler + DLQ)
│   └── moderation-lib/   # TextSanitizer, @MaxCodePoints, TextModerator (blocklist tiếng Việt + spam), TextClassifier (/moderate/text), AdminTermsSync, KeywordFilter — caption và comment
└── services/
    ├── api-gateway/      :8080  WebFlux — KHÔNG dùng spring-boot-starter-webmvc
    ├── auth-service/     :8081  PostgreSQL + Security + Flyway
    ├── user-service/     :8082  PostgreSQL + Security + Flyway
    ├── video-service/    :8083  MongoDB
    ├── media-worker/     :8084  MinIO + Kafka consumer
    ├── interaction-service/ :8085  Cassandra + Redis
    ├── story-service/    :8086  MongoDB (TTL) + MinIO + Kafka — story ảnh/video, kiểm duyệt qua media-worker
    ├── recommendation-service/ :8087  Kafka consumer + Redis (trending + feed cá nhân hoá, xếp hạng qua rank-service)
    ├── chat-service/     :8088  MongoDB + WebSocket
    ├── notification-service/   :8089  MongoDB + FCM
    ├── search-service/   :8095  Elasticsearch
    ├── admin-service/    :8096  PostgreSQL + Security
    ├── analytics-service/ :8097  ClickHouse / Kafka consumer (+ sink dữ liệu huấn luyện)
    ├── rank-service/      :8098  **Python** FastAPI + LightGBM — KHÔNG phải module Maven
    └── moderation-service/ :8099  **Python** FastAPI + ViT NSFW classifier + CLIP zero-shot (bạo lực/máu me/ma tuý/tai nạn/cờ bạc) + PhoBERT text classifier — KHÔNG phải module Maven
```

`rank-service` và `moderation-service` là hai ngoại lệ không phải Java: chúng không nằm trong `pom.xml` gốc, `./mvnw` không build chúng, và `make build` không đụng đến. Build/test qua `make rank-up`/`make rank-test` và `make moderation-up`/`make moderation-test`. Cả hai chỉ đến được từ mạng nội bộ, không có route ở gateway và không xác thực JWT — xem `docs/ranking-model.md` và `docs/moderation.md`.

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
  - Elasticsearch: index `processed_events` với `op_type=create`, 409 nghĩa là đã xử lý (xem `search-service/IdempotentEventProcessor`). Ở đây check-then-act còn hỏng nặng hơn: document vừa ghi chưa đọc lại được cho tới lần refresh kế tiếp (mặc định 1s) nên mọi redelivery trong cửa sổ đó **luôn** lọt qua. `op_type=create` giải quyết ở shard, không phụ thuộc refresh
  - Counter trên Elasticsearch: scripted partial update + `retry_on_conflict`, KHÔNG đọc-sửa-ghi cả document — `save()` ghi đè cả field mà consumer khác vừa đổi (xem `search-service/SearchIndexWriter`)
- Event class lấy từ `libs/event-schema`
- **Topic trộn nhiều event type** (`admin.moderation-events`, `admin.report-events`, `video.video-events`): payload JSON không có field phân biệt loại — dùng Kafka header `eventType` (đọc qua `@Header(name = "eventType")`) để route, KHÔNG suy đoán từ shape JSON. Thiếu route thì Jackson **vẫn parse được** sang class sai với mọi field vắng mặt là null, không exception, không log — service chỉ âm thầm làm sai việc. `video.video-events` mang `VideoPublishedEvent` + `VideoDeletedEvent` + `VideoPurgedEvent` + `VideoVisibilityChangedEvent` cùng key `videoId`, nên Kafka đảm bảo thứ tự. **Xoá video là hai event, không phải một**: `VideoDeletedEvent` phát NGAY khi owner xoá — nó là thứ duy nhất gỡ video khỏi search index và feed recommendation, hoãn nó là để video đã xoá vẫn tìm thấy được; `VideoPurgedEvent` phát khi hết `video.trash.retention` (mặc định 30 ngày) và chỉ media-worker xử lý — đó mới là lúc media bị xoá khỏi MinIO, giữ thùng rác để admin còn xem lại được. Đừng gộp lại làm một. Ngoại lệ: video bị xoá trước khi publication kịp announce vẫn phát cả hai — file raw đã nằm trong MinIO và `VideoPurgedEvent` là thứ duy nhất còn nhắc tới key đó. Mọi consumer của `VideoDeletedEvent`/`VideoPurgedEvent` phải no-op với `videoId` lạ. Consumer coi **header vắng mặt = `VideoPublishedEvent`** (producer đời cũ chỉ gửi loại đó)
- **kafka-lib usage**: dependency `<artifactId>kafka-lib</artifactId>`, auto-config qua Spring Boot — không cần `@Configuration` cục bộ. Hai thứ độc lập nhau:
  - `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` cho mọi `@KafkaListener`. Hai chính sách, phân theo exception (`KafkaConsumerAutoConfiguration.isOutage`, đọc cả chuỗi cause): **dependency chết** (lỗi Spring dịch sang `TransientDataAccessException`/`DataAccessResourceFailureException`, không lấy được connection, socket/DNS, `RetriableException` của Kafka) → retry KHÔNG giới hạn, backoff mũ trần 30s — partition đứng chờ, không mất event; **mọi lỗi khác** (JSON hỏng, bug) → retry 3 lần rồi `<topic>.DLT` + log ERROR + counter `kafka_consumer_dead_letters_total`. Trước đây mọi lỗi đều vào DLT sau ~3s, nên ES/Postgres restart vài giây là mất event vĩnh viễn (không gì đọc lại DLT). Client báo "unavailable" kiểu khác thì dịch sang transient ở chỗ bắt lỗi (xem `search-service/ElasticsearchStatus.classify`) — đang dùng: `auth-service`, `user-service`, `video-service`, `recommendation-service`, `media-worker`, `search-service`, `interaction-service`, `analytics-service`, `chat-service`, `notification-service`, `story-service`
  - `OutboxDispatcher` (mark sau ack, xem §Publish outbox) — đang dùng: `auth-service`, `admin-service`, `video-service`, `user-service`, `story-service`
  - `OutboxBacklog`: mỗi outbox khai một bean (`config/OutboxMetricsConfig`) → gauge `outbox_pending_events` + `outbox_oldest_pending_age_seconds{outbox}`, đọc từ storage của service ở **mỗi lần scrape**. Dispatcher không tự đếm được: nó chỉ thấy batch được đưa vào, nên poller chết trông y hệt outbox rảnh. Thêm outbox/slot mới (vd. thêm poll vào `VideoEventPublisher`) thì thêm bean cùng filter với poll đó
  - `DltReplay` (`make dlt-list TOPIC=x.DLT`, `make dlt-replay TOPIC=x.DLT OFFSET=p:o|ALL=1 [FORCE=1]`): gửi record trong DLT về topic gốc, giữ key/value/header, bỏ `kafka_dlt-*`. Record mà partition gốc đã có record mới hơn **cùng key** thì bị bỏ qua — replay nó là đảo thứ tự event — trừ khi `FORCE=1` sau khi người đã xem. Chỉ chạy sau khi đã deploy bản sửa lỗi làm record vào DLT
  - interaction có consumer (`AdminModerationEventConsumer`) nhưng không có outbox: Cassandra không có transaction đa bảng để ghép outbox vào, nên `InteractionEventPublisher` chờ broker ack rồi mới coi là xong
  - notification cũng không có outbox: `NotificationEventPublisher` chỉ announce (`notification.created`) bản ghi inbox vừa lưu để chat-service đẩy xuống socket. Mất event = mất badge realtime, lần fetch inbox kế tiếp sửa lại; còn throw ở đây sẽ bắt consumer redeliver và ghi bản ghi lần hai

### JWT Authentication & security-lib
- **security-lib usage**: 9 services (admin, analytics, chat, interaction, notification, recommendation, story, user, video) dùng centralized `security-lib` để validate JWT token
  - Dependency: `<artifactId>security-lib</artifactId>`
  - Auto-configured via Spring Boot auto-configuration (`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`)
  - Services KHÔNG cần `@Configuration` cục bộ cho JWT — được inject tự động
  - Fail-fast: kiểm tra JWT_SECRET có tồn tại khi service startup
- **Token type — BẮT BUỘC**: access token và refresh token cùng ký bằng 1 secret, phân biệt bằng claim `tokenType` (`access`/`refresh`). Mọi nơi authenticate từ bearer token PHẢI dùng `JwtProvider.isValidAccessToken()` (hoặc `parseAccessToken()` khi cần claims — cùng allow-list, chỉ verify chữ ký một lần thay vì check rồi `extractClaims()` lần nữa), KHÔNG dùng `isValid()` — nếu không refresh token (7 ngày) sẽ đăng nhập được như access token
- **Revocation**: hai loại key, đọc bởi 3 read side (api-gateway, auth-service filter, `RevokedTokenChecker` của security-lib). Tên key + phép so mốc cắt nằm ở `crypto-lib/RevocationKeys` — sửa ở đó, KHÔNG viết chuỗi `"auth:blacklist:..."` trong service. Lệnh Redis vẫn để ở từng read side vì gateway reactive, hai bên kia blocking — nhưng cả hai key đọc bằng MỘT MGET (`RevocationKeys.keysOf` + `isRevoked`), không phải hai lệnh nối tiếp:
  - `auth:blacklist:{jti}` — logout, giết đúng 1 access token
  - `auth:blacklist:user:{userId}` = mốc epoch millis — giết MỌI access token cấp trước mốc đó, dùng khi cả phiên phải chết cùng lúc (reset password, phát hiện refresh token replay). Access token là stateless nên không có danh sách jti để chặn; so sánh bằng `JwtProvider.issuedAtMillis(claims)` (claim `iatMs`, vì `iat` chuẩn chỉ có độ phân giải giây → không phân biệt được token cấp cùng giây với mốc cắt)
  - Tất cả fail-open khi Redis chết; service không có Redis trên classpath nhận bản no-op
- **Refresh rotation**: `/refresh` xoay token bằng `RefreshTokenRepository.claimForRotation()` — một `UPDATE ... WHERE rotated_at IS NULL AND revoked_at IS NULL` set cả `revoked_at` lẫn `rotated_at`, KHÔNG phải đọc-kiểm-tra-rồi-ghi. Số row ảnh hưởng là kết quả kiểm tra. Đọc rồi check `rotatedAt == null` rồi mới ghi thì hai request đồng thời đều thấy null, đều xoay, và hai chain cùng sống — đúng kịch bản attacker + user thật mà replay detection sinh ra để bắt. Thiếu `revoked_at IS NULL` thì refresh đã đọc row trước khi logout/reset password commit vẫn xoay được, và chain mới sống qua cả reset. Vì vậy 0 row có hai nghĩa, phân biệt bằng cách đọc lại row: `rotated_at` có giá trị là thua race (áp luật replay); chỉ có `revoked_at` là logout/reset thắng → 401 thường, KHÔNG revoke mọi phiên, không thì logout ở 1 máy đá văng mọi máy khác. Logout revoke bằng `RefreshTokenRepository.revoke()` (UPDATE có điều kiện), KHÔNG `save()` entity: Hibernate ghi lại mọi cột, entity đọc trước khi refresh commit sẽ ghi `rotated_at` về NULL và xoá dấu vết replay
- **Replay detection**: trình lại token ĐÃ rotate mà chưa hết hạn → `SessionRevoker.revokeAllSessions()` giết toàn bộ phiên của user. Chỉ `rotated_at` mới kích hoạt, KHÔNG phải `revoked_at` (lý do như trên). Ngoại lệ là **grace window** `auth.jwt.rotation-grace-millis` (10s): trong khoảng đó chỉ trả 401, không giết phiên — client retry một request `/refresh` mất response trông y hệt kẻ trộm, khác nhau ở thời điểm. Client thật chỉ refresh khi access token hết hạn (15 phút sau), kẻ trộm còn muộn hơn. Nới grace = nới cửa sổ replay, giữ ở scale của request timeout. `SessionRevoker` chạy `REQUIRES_NEW` vì luồng replay revoke xong rồi throw; chạy chung transaction thì throw sẽ rollback luôn việc revoke
- **Xoá comment**: `admin.moderation-events` mang `CommentRemovedEvent`; `interaction-service/AdminModerationEventConsumer` gọi `CommentService.removeByAdmin()`. Không cần inbox: write duy nhất là LWT `markDeletedIfNotDeleted`, redelivery thấy comment đã xoá thì trả false và không giảm counter lần hai. `targetId` của action là chuỗi `"videoId:commentId"` (xem `admin-service/CommentTarget`) — comment id một mình vô dụng vì `comments_by_video` partition theo video. Không có restore: un-delete chỉ nằm ở compensation trong interaction-service, có điều kiện đúng lần xoá nó vừa ghi; un-delete vô điều kiện sẽ hồi sinh cả comment người khác xoá. **Danh sách comment thì đọc từ interaction-service** — `GET /api/v1/interactions/admin/videos/{videoId}/comments` (`AdminCommentDirectory`), theo từng video vì không có bảng nào xếp comment toàn sàn theo thời gian
- **Ban**: `admin.moderation-events` mang `UserBannedEvent`/`UserUnbannedEvent`; `auth-service/AdminModerationEventConsumer` đặt `UserStatus.BANNED` rồi gọi `SessionRevoker` — chỉ đổi status là chưa đủ, access token stateless vẫn sống hết 15 phút. Không cần inbox: cả hai thao tác là gán trạng thái (redelivery ra cùng kết quả) và topic key theo `userId` nên ban/unban của một account giữ đúng thứ tự. Hai đường vào: resolve một report, hoặc `POST /api/v1/admin/users/{id}/ban|unban` thẳng từ console (cùng `ModerationAction`, `reportId` null); cả hai nhận `banDays` (null = vĩnh viễn). **Ban ẩn cả nội dung**: video-service và search-service tự giữ bản ghi ban theo user (`owner_bans`, có guard `changedAt` chống event cũ đến muộn) và lọc lúc đọc với `bannedUntil > now` — KHÔNG đóng dấu lên từng video, vì ban tạm hết hạn do `ExpiredBanSweep` gỡ mà **không phát event**, và publication đang trên đường sẽ lọt dấu. Feed recommendation + trending hydrate qua `/videos/batch` nên được lọc ở video-service; comment thì interaction-service tự giữ `user_bans` (Cassandra, cùng guard `changed_at`) và lọc lúc liệt kê, counter không đổi. **Account ADMIN không bị ban**: auth-service là nơi duy nhất biết role nên consumer bỏ qua (log warn) — một phiên admin bị chiếm không được khoá ngoài mọi admin khác. admin-service hỏi role trước khi ghi ban (`AccountRoleClient`, gọi `GET /api/v1/auth/admin/users/{id}` bằng chính token của console) và trả 400 — không thì audit log ghi "đã ban", report bị đóng, và video của admin bị ẩn cho một lệnh ban không bao giờ có hiệu lực. Fail-open khi auth-service không trả lời (auth vẫn tự từ chối); tự ban vẫn chặn trước, không cần gọi. **Danh sách user thì nằm ở auth-service** — `GET /api/v1/auth/admin/users` (`AdminUserDirectory`), vì đó mới là DB sở hữu `status`/`role`; admin-service không được đọc DB service khác và proxy qua HTTP chỉ thêm một điểm hỏng
- **Download your data** (`user-service/DataExportWorker`, `docs/user-service-api.md` §3.21): job nền KHÔNG có token user, nên đọc video/interaction/chat qua prefix internal (`DataExportSources`, không fallback — service chết thì build lỗi và thử lại sau lease, đừng "sửa" thành bỏ qua section, file thiếu dữ liệu tệ hơn file đến muộn). Mọi chuyển trạng thái là UPDATE có điều kiện trên `status`; `complete` trả 0 nghĩa là chủ đã huỷ giữa chừng → xoá file vừa ghi. Một request `PROCESSING`/user do partial unique index, không chỉ do check trong service
- **Tự đóng tài khoản** (`auth-service/AccountClosureService`, spec `docs/superpowers/specs/2026-10-17-account-deactivation-design.md`): deactivate / xoá sau 30 ngày, xác minh lại bằng mật khẩu / mã email / token provider, mở lại bằng chính lần đăng nhập kèm `reactivate: true`. `auth.account-events` mang `AccountStateChangedEvent`; video/search/interaction ghi `deactivatedAt` lên **cùng bản ghi ban** nhưng là một cặp field độc lập với guard riêng (`accountStateChangedAt`) — ẩn = `bannedUntil > now` **hoặc** `deactivatedAt != null`. ĐỪNG gộp vào field của ban: unban sẽ mở lại tài khoản chủ đã đóng. Bẫy: bản ghi do quyết định kia tạo có guard của mình là null, nên guard phải khớp cả null (Mongo `orOperator(is(null), lte)`, Cassandra thêm `IF col = null`), không thì upsert va `_id` / LWT không bao giờ apply. user-service tự ẩn profile (404, loại khỏi list/search/batch), counter giữ nguyên tới khi xoá thật. `User.unban()` trả về `DEACTIVATED` nếu chủ đã đóng trước đó
- **Tài khoản ADMIN**: `register` luôn tạo `UserRole.USER`. Admin đầu tiên do `AdminAccountBootstrap` cấp lúc startup từ `auth.admin.email` + `auth.admin.password` (env `ADMIN_EMAIL`/`ADMIN_PASSWORD`); thiếu một trong hai thì không làm gì. Account đã tồn tại với email đó được promote, KHÔNG ghi đè password — nhưng chỉ khi account đó đã verify email. Account chưa verify thì bỏ qua (log error): ai cũng đăng ký được email không phải của mình, kèm password tự chọn hoặc identity Facebook, và social login không qua factor thứ hai của admin. Cũng vì vậy account ADMIN không đăng nhập được bằng Google/Facebook (`auth-service/SocialSignInPolicy`, 403 `SOCIAL_SIGN_IN_NOT_ALLOWED`) — chỉ password + Turnstile + OTP
- **Login**: bắt buộc `emailVerified` — chưa verify thì 403 `EMAIL_NOT_VERIFIED` (khác `INVALID_CREDENTIALS` có chủ đích: client cần biết để mở màn hình gửi lại OTP). Định danh chứa `@` chỉ tra theo email (`UserRepository.findSignInAccount`) và username không được chứa `@` (`UsernameRule`, chung cho register/complete/đổi username) — trước đây tra username trước, nên ai đăng ký username `victim@x.com` là chặn được đăng nhập bằng email của nạn nhân
- **Mật khẩu ≤ 72 byte UTF-8** (`@FitsBcrypt`, không phải 72 ký tự): Spring Security từ 6.3.8 ném `IllegalArgumentException` khi hash mật khẩu dài hơn (CVE-2025-22228) — thiếu validate là 500. `matches()` vẫn chấp nhận mật khẩu dài cũ, nên account cũ không bị khoá
- **Endpoint nội bộ** (`/api/v1/users/internal/**`, `/api/v1/interactions/internal/**`, `/api/v1/videos/internal/**`, `/api/v1/conversations/internal/**`, `/api/v1/stories/internal/**`, `/api/v1/videos/*/policy`): không nhận JWT của user mà kiểm header `X-Internal-Token` (`common-lib/InternalApiToken`, so constant-time). `SecurityConfig` khai `.access(new InternalCallerAuthorization(internalApiToken))`, KHÔNG `permitAll()`: gateway `denyAll` các path này, nhưng mỗi service vẫn nghe trên port riêng và ai tới được port đó là đã đi vòng qua gateway. Client gọi chúng thêm `.defaultHeader(InternalApiToken.HEADER, internalApiToken.value())`; KHÔNG gắn vào client gọi bên thứ ba (OAuth, Turnstile, avatar host). Gateway chỉ gửi token sang **auth-service**, để auth-service tin `X-Client-Ip` (`SecurityEventRecorder`). Gửi lên mọi route thì mọi request của user đều thành internal caller. Env `INTERNAL_API_TOKEN` phải **giống nhau ở mọi service**: lệch thì call nội bộ bị 403 và caller rơi vào nhánh fail-open/fail-closed của nó. Fail-fast ngoài profile local/test (thiếu, < 32 byte, hoặc trùng giá trị mặc định local); bean `@Lazy` nên service không dùng thì không cần env. Xoay vòng bằng `INTERNAL_API_TOKEN_ROTATING` (chỉ nhận, không gửi). Thêm endpoint nội bộ mới → `.access(...)` ở service VÀ `denyAll` ở gateway
- **Exceptions (NOT using security-lib)**:
  - `api-gateway`: dùng WebFlux (không có servlet API) — giữ JwtConfig/JwtProperties riêng
  - `auth-service`: cấp phát JWT token (khác config: accessTokenExpiryMillis/refreshTokenExpiryMillis, prefix `auth.jwt`) — giữ file riêng + đã có fail-fast validation

### Kiểm duyệt video tự động
- Video **không** publish thẳng sau khi transcode. `Video.markTranscoded()` đặt `PENDING_MODERATION`; chỉ `VideoModerationCompletedEvent` mới đưa nó sang `PUBLISHED`/`PENDING_REVIEW`/`REJECTED`. Đừng "sửa" chỗ này về `PUBLISHED` — đó là toàn bộ điểm của tính năng
- Mọi read path lọc `status == PUBLISHED` (whitelist, không phải blacklist), nên thêm status mới là mặc định vô hình với viewer. Giữ nguyên kiểu lọc đó
- **Thất bại không bao giờ là pass**: classifier chết → retry 3 lần → `REVIEW`, không phải `PUBLISHED`, cũng không phải `REJECTED`. `ModerationService.moderate()` không bao giờ throw, vì video không có verdict sẽ kẹt ở `PENDING_MODERATION` mà không có gì đẩy nó đi tiếp
- Ngưỡng nằm trong env của `moderation-service`, KHÔNG nằm trong media-worker: chúng thuộc về model, chỉnh là restart một container chứ không phải rebuild service Java. Đổi ngưỡng hay đổi weights thì bump `MODERATION_MODEL_VERSION`
- Điểm số lưu trên document (`video.moderation`) chứ không chỉ log — tune ngưỡng nghĩa là đối chiếu model chấm bao nhiêu với việc admin sau đó duyệt hay không, và log thì đã cuộn mất
- Frame lấy **rải đều toàn video**, không phải mấy giây đầu — xem `Ffmpeg.sampleFrames`
- **Bạo lực, máu me, ma tuý, tai nạn, cờ bạc** (`moderation-service/harm_classifier.py`, CLIP zero-shot): model NSFW chấm những thứ này ~0.02, nên chúng cần bộ chấm riêng. **Chỉ được giữ, không bao giờ REJECT** (`verdict.review_only`) — phim hành động, boxing, dao làm bếp đều cách "violence" một câu mô tả. Azure chỉ được hỏi khi `nsfw` là nhãn DUY NHẤT đang giữ video: Azure không có hạng mục ma tuý/cờ bạc, hỏi nó sẽ trả severity 0 và publish chính video vừa bị giữ. Thuốc lá/vape nằm trong danh sách mô tả *bình thường* có chủ đích (quyết định sản phẩm, có test). Danh sách mô tả CHÍNH LÀ model: thêm/sửa/xoá một dòng là đổi mọi điểm số → bump `HARM_MODERATION_MODEL_VERSION` và chấm lại tập đã gán nhãn. `moderation.maxScore` thuộc về `moderation.label`, không còn luôn là `nsfw`
- **Kiểm duyệt chữ** (`moderation-lib`, rules in-process): title/description/tag chấm lúc `POST /videos` — severity 3 → 422 theo trường, không lưu gì; severity 2 → `VideoPublishedEvent.textVerdict = REVIEW`. Verdict chữ **gộp ở media-worker** (`CaptionVerdict`), KHÔNG ở `Video.outcomeOf`: search-service và recommendation-service tự đọc `VideoModerationCompletedEvent` và publish khi `APPROVED`, nên chỉ giữ ở video-service là video vẫn tìm thấy được. Chữ không bao giờ đẩy video sang `REJECTED`. Comment: ALLOW / HOLD (lưu `status = HELD`, không index, không counter, không event — chủ video duyệt) / REJECT 422. Xoá comment HELD **không** được động vào counter (`softDelete` rẽ nhánh theo status). Term trong `blocklist-vi.txt` viết có dấu thì chỉ khớp chữ có dấu (`cặc` ≠ `các`) — đọc header file trước khi thêm. Đổi list thì bump `version:`
- **Kiểm duyệt chữ, Phase 2** — các chỗ dễ "sửa" nhầm:
  - **Classifier chữ fail-open**, ngược với frame (frame lỗi → `REVIEW`). Khi classifier được hỏi thì rules đã chạy xong, nên classifier chết chỉ là quay về Phase 1. Sau một lỗi thì nghỉ `cooldown` 30s mới hỏi lại. Classifier chỉ HOLD, không bao giờ REJECT.
  - **Bộ lọc comment của creator** đi đường user-service `/internal/settings` → video-service `/policy` (cache `user-settings:comment-policy:{id}`) → `CommentPolicy`. Không ai trả lời thì dùng mặc định, không giữ mọi comment. Bộ lọc của creator luôn có hiệu lực, kể cả khi `TEXT_MODERATION_ENFORCE=false`.
  - **Strike** nằm trong Redis: một lần rules từ chối (422) hoặc admin xoá comment là một strike. 3 strike trong 24h → `COMMENTING_RESTRICTED`, kiểm đầu tiên trong `addComment`. Redis chết → không strike, không chặn.
  - **Term do admin quản lý** được **poll** (`AdminTermsSync`, 60s), không qua Kafka: mọi instance đều cần mọi thay đổi, còn consumer group chỉ giao mỗi event cho một instance.
- **Story** (`story-service`, `docs/story-service-api.md`): cùng nguyên tắc nhưng không có hàng chờ admin. Story tạo ra ở `PENDING_MODERATION` (hạn trần 48h), `story.story-events` → media-worker (ảnh = 1 frame — trừ ảnh động GIF/APNG/WebP, moderation-service bung tối đa 16 frame rải đều, vì chấm như 1 ảnh là chỉ chấm frame đầu; video probe + rải frame, > 60s → REJECTED) → `media.story-moderation-events` → `ACTIVE` (24h tính từ lúc duyệt) / `HELD` / `REJECTED`. HELD và REJECTED chỉ chủ story thấy, rồi tự hết hạn. Media nằm dưới `stories/` — KHÔNG thêm prefix đó vào policy đọc ẩn danh của bucket: người xem chỉ nhận presigned GET 1h, cấp lúc họ được phép xem. Quyền xem PUBLIC/FRIENDS/PRIVATE theo đúng luật của video (`StoryAudience`), cộng ban/đóng tài khoản mà story-service tự giữ bản sao (`owner_bans`). Tài khoản riêng tư/follow/bạn bè thì **hỏi user-service mỗi lần đọc** (`FriendshipClient` → `/internal/friendship`, fail-closed), KHÔNG giữ bản sao từ `user.privacy-events`: bản sao chỉ biết thay đổi xảy ra lúc story-service đang nghe (topic giữ 7 ngày), nên tài khoản chuyển riêng tư trước đó sẽ bị coi là công khai
  - **Thông báo story** (`notification-service/StoryActivationEventConsumer`): story thành `ACTIVE` với `visibility` PUBLIC/FRIENDS thì story-service phát `StoryActivatedEvent` lên `story.activation-events`, key = id chủ story để bước collapse không bị story kế tiếp của chủ đó đua từ partition khác. KHÔNG phát lên `story.story-events`: `StoryModerationConsumer` của media-worker không route theo `eventType`, sẽ parse nó thành `StoryCreatedEvent` với field null mà không exception. Cờ outbox là `Story.activatedAt` (đặt khi `decide()` ra `ACTIVE`; story sống từ trước tính năng không có nên không bao giờ được báo) và `announcedAt` (đặt sau khi broker ack, đã đặt thì không báo lại dù đổi visibility); story PRIVATE để nguyên chưa đánh dấu nên mở rộng trong 24h thì được báo lúc đó. Event cố ý KHÔNG mang `visibility`: consumer **hỏi lại** `GET /api/v1/stories/internal/{storyId}/announcement` lúc chạy — 404 (xoá, hết hạn, chưa `ACTIVE`, chủ bị ban/đóng) hoặc PRIVATE thì không báo ai, còn story-service không trả lời thì ném để kafka-lib giao lại (ĐỪNG làm client fail-open kiểu `BlockClient`: rỗng nghĩa là event đã xong). Người nhận lấy từ `GET /api/v1/users/internal/story-audience` (PUBLIC → `FOLLOWERS`, FRIENDS → `FRIENDS`, keyset theo id, 500/trang), đã loại block hai chiều, tài khoản đã đóng và người mute chủ story, nên KHÔNG gọi `BlockClient` từng người. `NotificationType.STORY` nằm trong `collapsesRepeats()` với `referenceId` = id **chủ story** (không phải id story): mỗi người nhận có tối đa một mục cho mỗi chủ story trong 24h, và đó là thứ khiến fan-out hỏng giữa chừng được giao lại từ trang đầu mà không nhân đôi — đừng đổi `referenceId` hay bỏ STORY khỏi danh sách đó
- Chi tiết: `docs/moderation.md`

### Gọi service khác / Redis / JPA — bẫy hiệu năng đã gặp
- RestClient gọi service nội bộ: dùng `PooledRequestFactory.create(connect, read)` (common-lib), KHÔNG dùng `SimpleClientHttpRequestFactory` — HttpURLConnection chỉ giữ 5 kết nối idle mỗi host, quá 5 request đồng thời là mỗi call mở TCP mới. HTTP/1.1 được ghim vì uvicorn (rank/moderation) làm rơi body khi gặp h2c upgrade
- `executePipelined` cần `commons-pool2` trên classpath (video, interaction, recommendation đã có): không có pool, mỗi pipeline mở rồi đóng một kết nối TCP mới tới Redis
- Spring Data derived `...IgnoreCase` sinh `upper(col) = upper(?)`, index `lower(...)` không dùng được → seq scan. Cần index thì viết `@Query` với `lower(...)` (xem auth `UserRepository`)
- Endpoint batch (counts, like-status, repost-context, settings…) đọc cả trang trong một lệnh (MGET / `IN` / pipeline), không lặp từng id

### Spring Boot 4 — bẫy khi thêm dependency hay config
Mấy chỗ dưới đây sai thì vẫn compile, vẫn khởi động, chỉ là âm thầm không làm gì:
- **Auto-config đi theo module**: `spring-kafka` trần không có `KafkaTemplate` lẫn listener factory → dùng `spring-boot-starter-kafka`. `flyway-core` trần thì migration **không chạy** → `spring-boot-starter-flyway` (vẫn kèm `flyway-database-postgresql`). Inject `RestClient.Builder` cần `spring-boot-starter-restclient`. Test slice cần starter `-test` của nó: `spring-boot-starter-webmvc-test` (`@WebMvcTest`, `@AutoConfigureMockMvc`), `-data-jpa-test`, `-data-mongodb-test`, `-webflux-test`, `-micrometer-metrics-test` (`@AutoConfigureMetrics`)
- **Key cũ bị bỏ qua, không báo lỗi**: Mongo là `spring.mongodb.uri` — để `spring.data.mongodb.uri` thì service nối vào `localhost/test` (riêng `spring.data.mongodb.auto-index-creation` vẫn ở chỗ cũ). Route gateway nằm dưới `spring.cloud.gateway.server.webflux.*` — key cũ nghĩa là gateway không có route nào
- **Jackson 3**: package `tools.jackson.*` (annotation vẫn `com.fasterxml.jackson.annotation`), `JacksonException` là unchecked, `ObjectMapper` immutable (không `registerModule`, java.time có sẵn), bean của Boot là `JsonMapper`. Jackson 3 mặc định ném lỗi khi field primitive thiếu/null — payload từ trước khi field tồn tại (event cũ, service chưa deploy, cache) sẽ hỏng; `common-lib/PrimitiveDefaultsModule` (đăng ký qua `META-INF/services`) trả lại hành vi Jackson 2 cho mọi mapper tự tìm module (bean của Boot, converter/codec mặc định của RestClient/WebClient). Mapper tự `new ObjectMapper()` thì không có — trong test dùng `JsonMapper.builder().findAndAddModules().build()`. JSON của mọi event bị ghim ở `event-schema/EventJsonCompatibilityTest` (bản Jackson 2 ghi ra): test đó đỏ nghĩa là đổi contract với consumer còn chạy bản cũ và record còn nằm trên topic/DLT/outbox
- **DLT**: Spring Kafka 4 đổi tên mặc định sang `<topic>-dlt`; kafka-lib giữ `<topic>.DLT`. Tự dựng `DeadLetterPublishingRecoverer` thì qua `KafkaConsumerAutoConfiguration.deadLetterRecoverer`, không `new` thẳng
- **Filter là bean + nằm trong security chain** (như `JwtAuthenticationFilter`) thì khai thêm `FilterRegistrationBean` với `setEnabled(false)` (xem `security-lib/JwtSecurityAutoConfiguration`): Boot còn đăng ký nó làm servlet filter chạy TRƯỚC chain, nó gán authentication, `SecurityContextHolderFilter` thay context, bản trong chain tự bỏ qua vì `OncePerRequestFilter` → mọi request tới controller đều ẩn danh
- **Test**: `@MockitoBean`/`@MockitoSpyBean` của Spring; `@MockBean`/`@SpyBean` đã bị xoá

### Dev-only affordances — tắt mặc định, KHÔNG bật trên production
Test thủ công (Postman) cần đọc OTP mà không có mailbox thật. Trước đây in thẳng bằng
`log.warn("[DEV ONLY ...]")`; giờ nằm sau một cờ tắt mặc định:

| Cái gì | Ở đâu | Bật thế nào | Rủi ro nếu bật trên production |
|---|---|---|---|
| In OTP ra log (verify email + social link + reset password + admin login) | `auth-service/event/local/EmailNotificationListener.logOtp` | `auth.otp.log-to-console`, env `OTP_LOG_TO_CONSOLE`, mặc định `false` | OTP hiện nguyên văn trong log — ai đọc được log là chiếm được tài khoản bất kỳ. Riêng mã social link còn tệ hơn một bậc: nó là nửa còn lại của việc gắn tài khoản provider vào tài khoản sẵn có, nên chỉ cần đọc log rồi tạo một tài khoản Facebook khai email nạn nhân là chiếm được. Mã admin login là factor thứ hai của console — đọc log + biết password là vào được admin |

**Trước mỗi lần deploy thật**: `grep -rn "DEV ONLY" services/` phải trả về rỗng, và `OTP_LOG_TO_CONSOLE` không được set ở bất kỳ env nào.

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
make image SERVICE=auth-service   # Docker image 1 service Java (Dockerfile chung ở root)
make help           # Xem tất cả lệnh
```

Actuator: mọi service Java mở `/actuator/health|info|prometheus` không cần token (cấu hình chung ở `common-lib/ManagementDefaults`, quyền ở `security-lib/ActuatorSecurityAutoConfiguration`; auth-service tự permit). api-gateway để actuator ở `MANAGEMENT_PORT` (8180), KHÔNG để chung port public 8080.

## 6. Key Rules — KHÔNG được vi phạm
- [ ] KHÔNG query trực tiếp DB của service khác — gọi qua HTTP API hoặc Kafka event
- [ ] KHÔNG dùng Float/Double cho tiền — dùng `BigDecimal`
- [ ] KHÔNG hard delete — chỉ soft delete (`deletedAt`). **Ngoại lệ: bảng hạ tầng** (`refresh_tokens`, `verification_tokens`, `outbox_events`, `inbox_events`, `processed_events`) — không phải entity nghiệp vụ, không kế thừa `BaseEntity`, và cả vấn đề của chúng là số row quá nhiều nên đánh dấu `deletedAt` không giải quyết được gì. Xoá bằng job retention (`auth-service/ExpiredRecordCleanup`) hoặc TTL index (video-service `processed_events`). Retention chỉ được xoá row đã hết tác dụng: outbox **phải** có `published_at IS NOT NULL` — row chưa publish là event không tồn tại ở đâu khác, xoá theo tuổi là mất vĩnh viễn
- [ ] KHÔNG `@Autowired` field injection
- [ ] KHÔNG dùng `@Data` trên `@Entity`
- [ ] KHÔNG lưu sensitive data (password, token thô) vào Redis/log
- [ ] Mọi Kafka consumer PHẢI idempotent (claim eventId trước khi xử lý, xem §Kafka)
- [ ] KHÔNG để video lên feed mà chưa qua kiểm duyệt — xem §Kiểm duyệt video tự động
- [ ] MongoDB service: BẬT `spring.data.mongodb.auto-index-creation` — mặc định TẮT từ Spring Data Mongo 3.x, `@Indexed`/`@CompoundIndex` sẽ im lặng không được tạo
- [ ] `api-gateway` dùng WebFlux — KHÔNG import `spring-boot-starter-webmvc`
- [ ] **Feature của ranking model chỉ được định nghĩa một chỗ**: `services/rank-service/features.py`. Đổi tên, đổi thứ tự, hay đổi công thức một feature ở một phía (Java `CandidateFeatures` / Python `FEATURE_NAMES` / cột ClickHouse) mà không đổi phía kia là lỗi **không có triệu chứng**: mô hình vẫn trả về số, API vẫn 200, chỉ có feed là tệ đi. Xem `docs/ranking-model.md` §2 trước khi động vào

## 7. Khi Claude sinh code — checklist
- [ ] Package đúng convention: `com.tiktok.{service}.{layer}`
- [ ] Entity kế thừa `BaseEntity` từ `common-lib`
- [ ] DTO dùng Java `record` (hoặc `@Value` nếu cần builder)
- [ ] Mapper dùng MapStruct
- [ ] Service dùng interface + impl
- [ ] Constructor injection (`@RequiredArgsConstructor`)
- [ ] Flyway SQL nếu là PostgreSQL service
- [ ] Không có business logic trong Controller hay Entity
