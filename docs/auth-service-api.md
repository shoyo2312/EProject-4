# Auth Service — API Contract cho Flutter Mobile

Tài liệu này mô tả **những gì client (Flutter/Dart) cần biết** để tích hợp đúng với `auth-service`. Không phải tài liệu thiết kế backend — chỉ phần hợp đồng API (contract).

## 1. Tech stack liên quan đến client

| Thành phần | Công nghệ |
|---|---|
| Giao thức | REST/HTTP, JSON |
| Auth | JWT (access token + refresh token), Bearer scheme |
| Entry point cho mobile | **api-gateway** (`:8080`), KHÔNG gọi thẳng `auth-service:8081` |
| Base URL (qua gateway) | `http://<gateway-host>:8080/api/v1/auth` |
| Docs tương tác | `http://localhost:8081/swagger-ui.html` (chạy trực tiếp auth-service khi dev) |

> Flutter luôn gọi qua gateway (`:8080`), vì gateway còn áp thêm rate-limit theo IP và sẽ là nơi route tới các service khác (`user-service`, `video-service`, `interaction-service`...) dùng chung 1 base URL.

## 2. Response envelope chuẩn

Mọi response (thành công lẫn lỗi) đều bọc trong `ApiResponse<T>`:

```json
// Success
{
  "success": true,
  "data": { ... },
  "code": null,
  "message": null,
  "timestamp": "2026-08-01T10:00:00Z"
}

// Error
{
  "success": false,
  "data": null,
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid username/email or password",
  "timestamp": "2026-08-01T10:00:00Z"
}
```

Gợi ý model Dart dùng chung cho toàn app:

```dart
class ApiResponse<T> {
  final bool success;
  final T? data;
  final String? code;
  final String? message;
  final DateTime timestamp;
}
```

Vì field `code`/`message` là `null` khi thành công (do `@JsonInclude(NON_NULL)` — có thể **vắng mặt hẳn** trong JSON, không phải `null` literal), parser JSON phía Dart cần treat missing key = null (mặc định của `json_serializable` đã đúng).

## 3. Endpoints

Base path: `/api/v1/auth`

### 3.1 `POST /register`
Không cần token. Trả `201 Created`.

Request:
```json
{
  "username": "minh_hung",   // 3-50 ký tự, bắt buộc
  "email": "a@b.com",         // định dạng email, tối đa 255, bắt buộc
  "password": "P@ssw0rd!"     // 8-100 ký tự, bắt buộc
}
```

Response `data` → `UserResponse`:
```json
{
  "id": 123456789012345,     // Long (Snowflake ID) — dùng String hoặc int64 phía Dart, KHÔNG parse thành double
  "username": "minh_hung",
  "email": "a@b.com",
  "role": "USER",             // enum: xem UserRole
  "status": "ACTIVE",         // enum: xem UserStatus
  "emailVerified": false,     // luôn false ngay sau khi đăng ký — xem mục 3.6
  "createdAt": "2026-08-01T10:00:00Z"
}
```

Lỗi có thể gặp: `VALIDATION_ERROR` (400), `USERNAME_ALREADY_EXISTS` (409), `EMAIL_ALREADY_EXISTS` (409).

Ngay sau khi tạo tài khoản thành công, server tự động gửi 1 email chứa mã OTP 6 số để xác thực email (xem mục 3.6). **Phải verify email xong mới login được** — gọi `/login` khi `emailVerified = false` trả `403 EMAIL_NOT_VERIFIED` (xem mục 3.2). Luồng đăng ký của app vì vậy là `register` → màn hình nhập OTP → `verify-email` → `login`, KHÔNG phải `register` → `login`.

### 3.2 `POST /login`
Không cần token. Trả `200 OK`.

Request:
```json
{
  "usernameOrEmail": "minh_hung",
  "password": "P@ssw0rd!"
}
```

Response `data` → `TokenResponse`:
```json
{
  "accessToken": "eyJ...",
  "refreshToken": "eyJ...",
  "expiresInMillis": 900000   // access token TTL = 15 phút
}
```

Lỗi: `VALIDATION_ERROR` (400), `INVALID_CREDENTIALS` (401), `EMAIL_NOT_VERIFIED` (403), `TOO_MANY_LOGIN_ATTEMPTS` (429 — sau 5 lần sai trong 15 phút, tính theo tài khoản, không theo IP).

`EMAIL_NOT_VERIFIED` (403) khác `INVALID_CREDENTIALS` (401) **có chủ đích**: mật khẩu đã đúng, chỉ thiếu bước verify. Client bắt riêng mã này và điều hướng thẳng sang màn hình nhập OTP (gọi `/resend-verification` nếu user không còn mã cũ) — đừng gộp chung vào thông báo "sai tài khoản hoặc mật khẩu".

Lưu ý về counter rate-limit: nhập đúng mật khẩu nhưng chưa verify email **không** bị tính là 1 lần login sai, nên user không bị khoá 429 vì lặp lại thao tác này.

### 3.3 `POST /refresh`
Không cần Bearer token (dùng chính `refreshToken`). Trả `200 OK`.

Request:
```json
{ "refreshToken": "eyJ..." }
```

Response: `TokenResponse` giống `/login` — access + refresh token **mới**. Refresh token cũ bị **xoay (rotate)** ngay lúc này: nó chết vĩnh viễn, client phải ghi đè bằng cặp token mới trước khi làm bất cứ việc gì khác.

Lỗi: `INVALID_REFRESH_TOKEN` (401) → hết hạn, không hợp lệ, hoặc **đã bị xoay rồi** → **buộc logout local, quay về màn hình đăng nhập**.

**Replay detection — bắt buộc client phải xử lý đúng:** trình lại 1 refresh token đã xoay mà chưa hết hạn được coi là dấu hiệu token bị đánh cắp → server **giết toàn bộ phiên của tài khoản đó** (mọi refresh token bị thu hồi + mọi access token đang sống bị vô hiệu ngay). Hậu quả với user: bị đá khỏi tất cả thiết bị. Ngoại lệ duy nhất là **grace window 10 giây** — trong 10s kể từ lúc token bị xoay, request trùng chỉ nhận `401` chứ không giết phiên, để client retry một `/refresh` bị mất response không bị phạt oan.

Hai luật rút ra cho Flutter, sai là user bị logout hàng loạt:
1. **Single-flight**: chỉ được có **đúng 1** request `/refresh` chạy tại một thời điểm. Nhiều request 401 song song phải cùng chờ chung 1 Future refresh (Dio interceptor + lock/`Completer`), không được mỗi request tự gọi `/refresh` bằng token cũ.
2. **Ghi token mới trước khi retry**: lưu cặp token mới vào secure storage **rồi** mới phát lại các request đang chờ. Đừng retry với token cũ còn nằm trong biến/bộ nhớ.

Không được retry `/refresh` sau khi đã nhận `401` — lần thử thứ hai nằm ngoài grace window sẽ kích hoạt replay detection và giết phiên trên mọi thiết bị của user.

### 3.4 `POST /logout`
Header `Authorization: Bearer <accessToken>` (optional nhưng nên gửi để access token bị blacklist ngay lập tức thay vì chờ hết hạn). Trả `204 No Content` (không có body).

Request:
```json
{ "refreshToken": "eyJ..." }
```

### 3.5 `GET /me`
**Bắt buộc** header `Authorization: Bearer <accessToken>`. Trả `200 OK`.

Response `data` → `UserResponse` (giống mục 3.1, bao gồm `emailVerified`).

Lỗi: `401 Unauthorized` nếu token thiếu/hết hạn/đã bị blacklist (do logout).

### 3.6 `POST /verify-email`
Không cần token. Trả `204 No Content`.

Request:
```json
{ "email": "a@b.com", "otp": "483920" }
```

Xác thực mã OTP 6 số gửi qua email lúc `register` (hoặc `resend-verification`). OTP hết hạn sau **15 phút**, dùng 1 lần.

Lỗi: `VALIDATION_ERROR` (400 — otp không đúng định dạng 6 số), `INVALID_OTP` (400 — sai email/otp, đã dùng, hoặc hết hạn), `TOO_MANY_OTP_REQUESTS` (429 — nhập sai OTP quá **5 lần/15 phút** theo email, độc lập với giới hạn gửi lại OTP ở mục 3.7).

### 3.7 `POST /resend-verification`
Không cần token. Trả `204 No Content` (luôn trả 204 kể cả khi email không tồn tại hoặc đã verify — tránh lộ thông tin tài khoản).

Request:
```json
{ "email": "a@b.com" }
```

Gửi lại OTP xác thực email mới (OTP cũ bị hủy). Giới hạn **3 lần / 15 phút** theo email.

Lỗi: `VALIDATION_ERROR` (400), `TOO_MANY_OTP_REQUESTS` (429).

### 3.8 `POST /forgot-password`
Không cần token. Trả `204 No Content` (luôn trả 204 dù email có tồn tại hay không — chống dò email).

Request:
```json
{ "email": "a@b.com" }
```

Nếu email tồn tại và tài khoản đang `ACTIVE`, gửi OTP reset password (15 phút, dùng 1 lần). Giới hạn **3 lần / 15 phút** theo email.

Lỗi: `VALIDATION_ERROR` (400), `TOO_MANY_OTP_REQUESTS` (429).

### 3.9 `POST /reset-password`
Không cần token. Trả `204 No Content`.

Request:
```json
{ "email": "a@b.com", "otp": "192837", "newPassword": "N3wP@ssw0rd" }
```

Đổi mật khẩu bằng OTP nhận từ `forgot-password`. **Sau khi đổi thành công, mọi phiên của tài khoản chết ngay lập tức** — không chỉ refresh token bị thu hồi, mà cả access token đang sống trên thiết bị khác cũng bị vô hiệu tức thì (không phải chờ hết 15 phút, cũng không phải chờ tới lần `/refresh` kế tiếp). Đây là chủ đích: reset password thường được dùng đúng lúc nghi tài khoản bị chiếm, nên phải cắt truy cập ngay.

Hệ quả cho client: thiết bị vừa reset password **cũng** mất phiên — sau `204` phải điều hướng về màn hình đăng nhập và cho user login lại bằng mật khẩu mới, không được giữ token cũ trong app.

Lỗi: `VALIDATION_ERROR` (400), `INVALID_OTP` (400), `TOO_MANY_OTP_REQUESTS` (429 — nhập sai OTP quá **5 lần/15 phút** theo email, độc lập với giới hạn gửi lại OTP ở mục 3.8).

## 4. Luồng xác thực (auth flow) cho Flutter

1. `register` → `verify-email` (OTP 6 số gửi qua mail) → `login` → lưu `accessToken` + `refreshToken` an toàn (`flutter_secure_storage`, **không** lưu SharedPreferences plaintext). Bỏ qua bước verify thì `login` trả `403 EMAIL_NOT_VERIFIED`.
2. Mọi request tới các service khác qua gateway: gắn header `Authorization: Bearer <accessToken>`.
3. Access token sống **15 phút**. Khi API trả `401`, gọi `/refresh` bằng `refreshToken` **đúng một lần, và chỉ một request tại một thời điểm** (xem luật single-flight ở mục 3.3); nếu `/refresh` lỗi (`INVALID_REFRESH_TOKEN`) → xoá token, điều hướng về màn hình login. **Không retry `/refresh`** — lần thử thứ hai bị coi là replay và giết phiên trên mọi thiết bị.
4. Refresh token sống **7 ngày** (`604800000ms`) nhưng **xoay mỗi lần dùng**: mỗi `/refresh` thành công trả về refresh token mới, phải ghi đè token cũ ngay. Nên chủ động refresh khi app resume nếu access token gần hết hạn, tránh chờ 401 (giảm giật UI).
5. Logout: gọi `/logout` với access token hiện có + refresh token, rồi xoá token local. Việc gọi API logout quan trọng vì nó blacklist access token trên server (Redis) — nếu chỉ xoá local, token cũ (nếu bị lộ) vẫn dùng được tới khi tự hết hạn. Logout chỉ giết **phiên hiện tại**; các thiết bị khác vẫn đăng nhập bình thường.
6. Có 2 trường hợp server giết **toàn bộ** phiên của user, app đang chạy sẽ đột ngột nhận `401` ở mọi request và `/refresh` cũng hỏng: reset password (mục 3.9) và replay detection (mục 3.3). Interceptor phải xử lý được tình huống này — xoá token, về màn hình login — chứ không loop retry.

Khuyến nghị dùng interceptor (Dio) để tự động gắn header và refresh-on-401, tránh lặp code ở từng repository. Interceptor bắt buộc phải có cơ chế khoá (single-flight) cho `/refresh`.

## 5. Enums

```java
// UserRole
USER, ADMIN

// UserStatus
ACTIVE, LOCKED
```

> Model Dart nên dùng enum + fallback "unknown" khi deserialize, để backend thêm giá trị mới không crash app cũ đang chạy production.

## 6. Bảng mã lỗi (`code`) đầy đủ

| `code` | HTTP status | Khi nào xảy ra |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Body không đúng ràng buộc (`@NotBlank`, `@Email`, `@Size`...) |
| `INVALID_CREDENTIALS` | 401 | Sai username/email hoặc password khi login |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token sai/hết hạn/**đã bị xoay** ở lần `/refresh` trước (xem mục 3.3 — trình lại token đã xoay còn kích hoạt replay detection, giết mọi phiên) |
| `EMAIL_NOT_VERIFIED` | 403 | Mật khẩu đúng nhưng email chưa xác thực → điều hướng sang màn hình nhập OTP, KHÔNG gộp với `INVALID_CREDENTIALS` |
| `TOO_MANY_LOGIN_ATTEMPTS` | 429 | Quá 5 lần login sai trong 15 phút (theo tài khoản) |
| `USERNAME_ALREADY_EXISTS` | 409 | Đăng ký trùng username |
| `EMAIL_ALREADY_EXISTS` | 409 | Đăng ký trùng email |
| `USER_NOT_FOUND` | 404 | (hiếm gặp qua API public, thường nội bộ) |
| `INVALID_OTP` | 400 | OTP sai, đã dùng, hết hạn, hoặc email không khớp (`verify-email`, `reset-password`) |
| `TOO_MANY_OTP_REQUESTS` | 429 | (a) Quá 3 lần gọi `resend-verification`/`forgot-password` trong 15 phút (theo email), **hoặc** (b) quá 5 lần nhập sai OTP trong 15 phút ở `verify-email`/`reset-password` (theo email) — 2 counter độc lập, client cần xử lý cả hai trường hợp |
| `CONCURRENT_MODIFICATION` | 409 | Hai request cùng sửa 1 bản ghi đúng lúc (vd. 2 lần `verify-email`/`reset-password` gửi song song). An toàn để **thử lại 1 lần** sau khi đọc lại trạng thái — khác hẳn các mã 409 khác vốn là lỗi nghiệp vụ không nên retry |
| `INTERNAL_ERROR` | 500 | Lỗi không xác định — hiển thị generic error, không show message raw cho user |

`message` là mô tả người-đọc-được (tiếng Anh), phù hợp để log/debug, KHÔNG nên hiển thị trực tiếp cho end-user — nên map `code` → chuỗi đa ngôn ngữ phía Flutter (i18n).

## 7. Rate limiting (ảnh hưởng tới UX)

- **Gateway** (`:8080`): giới hạn theo IP — 20 request/giây, burst 40 (áp dụng cho toàn bộ API, không riêng auth).
- **Auth-service**, 3 counter độc lập, đều theo **tài khoản/email** chứ không theo IP (nên đổi mạng/VPN không reset được):
  - Login sai: 5 lần/15 phút → `429 TOO_MANY_LOGIN_ATTEMPTS`. Tính theo tài khoản, nên gõ lúc `username` lúc `email` vẫn chung 1 counter.
  - Gửi OTP (`resend-verification`, `forgot-password`): 3 lần/15 phút → `429 TOO_MANY_OTP_REQUESTS`.
  - Nhập sai OTP (`verify-email`, `reset-password`): 5 lần/15 phút → `429 TOO_MANY_OTP_REQUESTS`.

Khi gặp `429`, UI nên disable nút tương ứng tạm thời + hiển thị thông báo rõ ràng, tránh user bấm liên tục. Riêng màn hình OTP nên đếm số lần nhập sai ở local để cảnh báo user trước khi chạm ngưỡng, vì server không trả về số lần còn lại.

## 8. Những điều KHÔNG nên làm phía Flutter

- Không tự parse/giải mã payload JWT để lấy role/user info hiển thị UI quan trọng (dùng `/me` là nguồn sự thật duy nhất) — access token có thể bị đổi cấu trúc mà không báo trước.
- Không lưu access/refresh token ở nơi không mã hoá.
- Không gọi thẳng `auth-service:8081` từ production build — luôn qua gateway `:8080`.
- Không hardcode message lỗi tiếng Anh từ server ra UI — dùng `code` để map.
- **Không gọi `/refresh` song song từ nhiều request** và không retry `/refresh` sau khi nó trả `401` — refresh token xoay mỗi lần dùng, trình lại token đã xoay bị coi là token bị đánh cắp và server giết phiên trên **mọi thiết bị** của user (xem mục 3.3).
- Không giữ lại access token cũ sau khi `reset-password` thành công — token đó đã chết ngay trên server (mục 3.9), giữ lại chỉ tạo ra chuỗi 401 khó hiểu.
- Không dùng refresh token làm `Authorization: Bearer` — 2 loại token ký cùng secret nhưng server phân biệt bằng claim `tokenType`, gửi nhầm sẽ luôn `401`.
