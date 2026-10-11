# API Gateway — Routing, Auth & Rate Limit

`api-gateway` (`:8080`) là **cửa duy nhất** cho client (Flutter mobile + Next.js web). Mọi doc `*-api.md` khác đều giả định request đi qua đây. Tài liệu này gom những thứ gateway tự làm, không lặp lại ở từng service.

Stack: Spring Cloud Gateway (**WebFlux/reactive** — KHÔNG có servlet API, KHÔNG dùng `spring-boot-starter-webmvc`). Redis cho rate-limit. JWT config riêng (`JwtConfig`/`JwtProperties`, prefix `jwt.secret`) vì `security-lib` là servlet-only — xem `CLAUDE.md` §JWT.

## 1. Bảng route

| Route id | Path predicate | Đích (env var, default) |
|---|---|---|
| auth-service | `/api/v1/auth/**` | `AUTH_SERVICE_URI` → `:8081` |
| user-service | `/api/v1/users/**` | `USER_SERVICE_URI` → `:8082` |
| video-service | `/api/v1/videos/**` | `VIDEO_SERVICE_URI` → `:8083` |
| interaction-service | `/api/v1/interactions/**` | `INTERACTION_SERVICE_URI` → `:8085` |
| recommendation-service | `/api/v1/recommendations/**` | `RECOMMENDATION_SERVICE_URI` → `:8087` |
| search-service | `/api/v1/search/**` | `SEARCH_SERVICE_URI` → `:8095` |
| admin-service | `/api/v1/admin/**` | `ADMIN_SERVICE_URI` → `:8096` |
| analytics-service | `/api/v1/analytics/**` | `ANALYTICS_SERVICE_URI` → `:8097` |

**Không route qua gateway** (chỉ đến được từ mạng nội bộ): `media-worker` (`:8084`, không có REST API), `rank-service` (`:8098`, xem `docs/ranking-model.md` §5), `story/chat/notification` (chưa expose).

Route không khớp path nào → `404` với envelope `ApiResponse` (xem mục 4).

## 2. Xác thực JWT ở gateway

Gateway kiểm tra **có access token hợp lệ hay không**, KHÔNG kiểm tra role. Mỗi service phía sau tự validate lại token (`security-lib`, defense in depth) và tự enforce quyền (route `/api/v1/admin/**`: gateway chỉ đòi token hợp lệ, `ROLE_ADMIN` do `admin-service` tự check).

Converter đọc header `Authorization: Bearer <token>`. Thiếu prefix `Bearer ` → coi như không có token.

### Path công khai (không cần token)

| Pattern | Ghi chú |
|---|---|
| `/api/v1/auth/**` | Toàn bộ auth |
| `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | Docs |
| `GET /api/v1/videos/**` | Mọi GET của video (POST/DELETE vẫn cần token) |
| `GET /api/v1/recommendations/trending` | Chỉ trending; `GET /feed` cần token |
| `GET /api/v1/search/**` | Toàn bộ search |
| `GET /api/v1/interactions/videos/{id}/comments`, `/videos/{id}/counts`, `/videos/counts/batch`, `/videos/{id}/like-status` | Các `GET` mà chính interaction-service để public; khách nhận `liked: false` ở `like-status` |
| `GET /api/v1/interactions/users/{id}/reposts` | Tab Reposts của profile |
| `/ws/**` | Handshake chat: token nằm ở query param, chat-service tự validate |

Mọi request khác → **bắt buộc** token hợp lệ; thiếu/sai/hết hạn → `401` (envelope `ApiResponse`, `RestAuthenticationEntryPoint`). Không đủ quyền ở tầng gateway → `403` (`RestAccessDeniedHandler`) — hiếm, vì gateway không check role.

> Lưu ý cho các path public: **không gửi token** thì là khách. **Gửi token hỏng** (hết hạn, bị thu hồi, sai chữ ký) thì vẫn `401`, kể cả trên path public — gateway không lặng lẽ hạ xuống khách. Client xử lý như mọi `401`: `/refresh` **một lần** rồi gửi lại; refresh thất bại thì xoá token và gọi lại như khách. Hạ xuống khách thì người đã đăng nhập nhận dữ liệu sai mà không có gì báo (tim rỗng, video private của chính mình biến mất).

## 3. `GET /api/v1/me` — view "tài khoản của tôi" gộp sẵn

Endpoint **do chính gateway phục vụ** (không proxy). Gộp identity (auth-service `GET /api/v1/auth/me`) + social profile (user-service `GET /api/v1/users/me`) thành một response, để client không phải gọi 2 nơi rồi tự ghép.

**Bắt buộc** header `Authorization: Bearer <accessToken>`. Trả `200 OK`. Mỗi lời gọi downstream vẫn mang nguyên header đó — auth-service và user-service tự validate JWT.

Response `data` → `MeResponse`:
```json
{
  "id": 123456789012345,
  "username": "minh_hung",
  "email": "a@b.com",
  "role": "USER",
  "status": "ACTIVE",
  "createdAt": "2026-08-01T10:00:00Z",
  "displayName": "Minh Hùng",
  "bio": "Xin chào",
  "avatarUrl": "http://localhost:9000/video-media/avatars/123",
  "followerCount": 10,
  "followingCount": 5,
  "profileReady": true,
  "usernameChangeableAt": null,
  "displayNameChangeableAt": "2026-08-08T10:00:00Z"
}
```

- **`usernameChangeableAt`** (từ auth-service) / **`displayNameChangeableAt`** (từ user-service): khi nào được đổi username (1 lần / 30 ngày, `PUT /api/v1/auth/me/username`) và tên hiển thị (1 lần / 7 ngày, `PATCH /api/v1/users/me`) lần tới. `null` = đổi được ngay. `displayNameChangeableAt` cũng `null` khi `profileReady: false`.

- **`profileReady: false`** → user-service chưa tạo profile xong (Kafka `UserRegisteredEvent` chưa xử lý — thường vài trăm ms sau register). Khi đó `displayName`/`bio`/`avatarUrl`/`followerCount`/`followingCount` đều `null`. Gateway đã **retry ngầm** call user-service 2 lần (backoff 150ms) trên riêng `404` trước khi degrade — client vẫn nên xử lý được trạng thái này (hiện `id`/`username`/`email` trước, poll lại `/me` để lấy profile).
- Call auth-service **không** được degrade: identity là bắt buộc, lỗi của nó (401, 5xx, timeout) truyền thẳng ra client.
- Call user-service degrade khi lỗi khác `404` (timeout 3s, 5xx, connection refused) → `profileReady: false`, không làm hỏng cả request.

Lỗi: `401` (thiếu/sai token), hoặc lỗi propagate từ auth-service.

## 4. Envelope lỗi của chính gateway

Lỗi sinh ở tầng gateway (route không khớp, downstream chết/không kết nối được, 401/403) được `GlobalErrorAttributes` bọc về đúng dạng `ApiResponse` như mọi service:

```json
{
  "success": false,
  "code": "SERVICE_UNAVAILABLE",
  "message": "...",
  "timestamp": "2026-09-03T10:00:00Z"
}
```

`code` ở đây là **tên HTTP status** (`NOT_FOUND`, `SERVICE_UNAVAILABLE`, `GATEWAY_TIMEOUT`…), không phải mã nghiệp vụ như `USER_NOT_FOUND`. Client phân biệt được vì `success: false` + `code` viết hoa dạng status.

## 5. Rate limiting

`RequestRateLimiter` (Redis token bucket), **default-filter áp cho mọi route**:

- **20 request/giây, burst 40**, theo **key = client IP** — trừ admin đã đăng nhập, key theo tài khoản (`admin:<userId>`).
- Vượt ngưỡng → `429 Too Many Requests`.
- Đây là giới hạn **duy nhất** ở tầng gateway; auth-service có thêm rate-limit riêng theo tài khoản/email (xem `docs/auth-service-api.md` §7).

### Key resolver

`clientKeyResolver` (`RateLimiterConfig`):

- **Principal có `ROLE_ADMIN`** → key `admin:<userId>`. Admin console gọi gateway từ server của nó, nên mọi admin cùng một địa chỉ và vài người mở trang videos là cạn chung bucket 20 req/s. Key theo tài khoản không giả được: filter xác thực đã verify token trước khi tới route filter, token hỏng là 401 chứ không thành khách. **Chỉ admin** — ai cũng tạo được account thường, key theo account cho họ là phát bucket mới cho mỗi lần đăng ký.
- **Mọi request khác** → key theo IP client, cách xác định như dưới.

Xác định IP: bắt đầu từ `getRemoteAddress()`; **chỉ khi** địa chỉ đó thuộc `gateway.rate-limit.trusted-proxies` (env `RATE_LIMIT_TRUSTED_PROXIES`, mặc định loopback + dải private) thì mới đọc `X-Forwarded-For`, và đọc **từ phải sang trái**: còn là proxy tin cậy thì lùi thêm một hop, gặp địa chỉ đầu tiên không phải proxy thì đó là client. Entry không phải IP literal (hostname, `ip:port`…) thì dừng ở proxy vừa chuyển nó tới. Không có remote address thì literal `"unknown"`.

- Web client qua Next.js proxy `/api/*` từ server của nó → nếu chỉ dùng `getRemoteAddress()` thì **cả site chung một bucket 20 req/s** (một trang feed 20 video + lookup tác giả là hết). Vì thế cần `X-Forwarded-For`.
- Trước đây lấy **entry đầu tiên** — chính là phần client tự viết, proxy chỉ append vào cuối. Đổi IP bịa mỗi request là được bucket mới mỗi lần. Giờ các entry bên trái địa chỉ client không bao giờ được đọc, và gọi thẳng vào gateway từ địa chỉ public thì header bị bỏ qua.
- ⚠️ **Next.js rewrites không append**: nó chỉ set `X-Forwarded-For` khi request chưa có (`??=`), nên nếu Next.js được expose thẳng ra internet thì header client tự bịa đi qua nguyên vẹn và gateway không thể biết IP thật. Cần một proxy đứng trước Next.js ghi đè/append header này (Vercel edge đã làm; chạy Docker thì đặt nginx/Caddy phía trước). Khi đã biết địa chỉ proxy, thu hẹp `trusted-proxies` về đúng các địa chỉ đó.

Hệ quả cho mobile: nhiều thiết bị sau cùng một NAT chia nhau hạn mức 20 req/s. Đây là lý do các doc khác yêu cầu poll giãn dần, không gọi `/like-status` cho cả trang feed cùng lúc, dùng `GET /videos/batch` thay vì `GET /{id}` nhiều lần.

## 6. Sự kiện Kafka

Không có. Gateway không produce/consume event nào.
