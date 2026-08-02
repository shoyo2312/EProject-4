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

Ngay sau khi tạo tài khoản thành công, server tự động gửi 1 email chứa mã OTP 6 số để xác thực email (xem mục 3.6). Đăng ký xong client có thể `login` bình thường — **login không bị chặn** dù email chưa verify (xem `emailVerified` để tự quyết định có nhắc user xác thực hay không).

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

Lỗi: `VALIDATION_ERROR` (400), `INVALID_CREDENTIALS` (401), `TOO_MANY_LOGIN_ATTEMPTS` (429 — sau 5 lần sai trong 15 phút, tính theo tài khoản, không theo IP).

### 3.3 `POST /refresh`
Không cần Bearer token (dùng chính `refreshToken`). Trả `200 OK`.

Request:
```json
{ "refreshToken": "eyJ..." }
```

Response: `TokenResponse` giống `/login` (access + refresh token **mới**, refresh cũ nên coi là đã dùng).

Lỗi: `INVALID_REFRESH_TOKEN` (401) → hết hạn hoặc không hợp lệ → **buộc logout local, quay về màn hình đăng nhập**.

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

Lỗi: `VALIDATION_ERROR` (400 — otp không đúng định dạng 6 số), `INVALID_OTP` (400 — sai email/otp, đã dùng, hoặc hết hạn).

### 3.7 `POST /resend-verification`
Không cần token. Trả `204 No Content` (luôn trả 204 kể cả khi email không tồn tại hoặc đã verify — tránh lộ thông tin tài khoản).

Request:
```json
{ "email": "a@b.com" }
```

Gửi lại OTP xác thực email mới (OTP cũ bị hủy). Giới hạn **3 lần / 15 phút** theo email.

Lỗi: `VALIDATION_ERROR` (400), `TOO_MANY_OTP_REQUESTS` (429).

## 4. Luồng xác thực (auth flow) cho Flutter

1. `register` → `login` → lưu `accessToken` + `refreshToken` an toàn (`flutter_secure_storage`, **không** lưu SharedPreferences plaintext).
2. Mọi request tới các service khác qua gateway: gắn header `Authorization: Bearer <accessToken>`.
3. Access token sống **15 phút**. Khi API trả `401`, thử gọi `/refresh` bằng `refreshToken` **một lần**; nếu `/refresh` cũng lỗi (`INVALID_REFRESH_TOKEN`) → xoá token, điều hướng về màn hình login.
4. Refresh token sống **7 ngày** (`604800000ms`). Nên chủ động refresh khi app resume nếu access token gần hết hạn, tránh chờ 401 (giảm giật UI).
5. Logout: gọi `/logout` với access token hiện có + refresh token, rồi xoá token local. Việc gọi API logout quan trọng vì nó blacklist access token trên server (Redis) — nếu chỉ xoá local, token cũ (nếu bị lộ) vẫn dùng được tới khi tự hết hạn.

Khuyến nghị dùng interceptor (Dio) để tự động gắn header và refresh-on-401, tránh lặp code ở từng repository.

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
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token sai/hết hạn/đã dùng |
| `TOO_MANY_LOGIN_ATTEMPTS` | 429 | Quá 5 lần login sai trong 15 phút (theo tài khoản) |
| `USERNAME_ALREADY_EXISTS` | 409 | Đăng ký trùng username |
| `EMAIL_ALREADY_EXISTS` | 409 | Đăng ký trùng email |
| `USER_NOT_FOUND` | 404 | (hiếm gặp qua API public, thường nội bộ) |
| `INVALID_OTP` | 400 | OTP sai, đã dùng, hết hạn, hoặc email không khớp (`verify-email`) |
| `TOO_MANY_OTP_REQUESTS` | 429 | Quá 3 lần gọi `resend-verification` trong 15 phút (theo email) |
| `INTERNAL_ERROR` | 500 | Lỗi không xác định — hiển thị generic error, không show message raw cho user |

`message` là mô tả người-đọc-được (tiếng Anh), phù hợp để log/debug, KHÔNG nên hiển thị trực tiếp cho end-user — nên map `code` → chuỗi đa ngôn ngữ phía Flutter (i18n).

## 7. Rate limiting (ảnh hưởng tới UX)

- **Gateway** (`:8080`): giới hạn theo IP — 20 request/giây, burst 40 (áp dụng cho toàn bộ API, không riêng auth).
- **Auth-service**: giới hạn login theo tài khoản — 5 lần sai/15 phút, trả `429 TOO_MANY_LOGIN_ATTEMPTS`.

Khi gặp `429`, UI nên disable nút login tạm thời + hiển thị thông báo rõ ràng, tránh user bấm liên tục.

## 8. Những điều KHÔNG nên làm phía Flutter

- Không tự parse/giải mã payload JWT để lấy role/user info hiển thị UI quan trọng (dùng `/me` là nguồn sự thật duy nhất) — access token có thể bị đổi cấu trúc mà không báo trước.
- Không lưu access/refresh token ở nơi không mã hoá.
- Không gọi thẳng `auth-service:8081` từ production build — luôn qua gateway `:8080`.
- Không hardcode message lỗi tiếng Anh từ server ra UI — dùng `code` để map.
