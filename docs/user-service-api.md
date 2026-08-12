# User Service — API Contract cho Flutter Mobile

Tài liệu này mô tả **những gì client (Flutter/Dart) cần biết** để tích hợp đúng với `user-service`. Không phải tài liệu thiết kế backend — chỉ phần hợp đồng API (contract). Đọc kèm `docs/auth-service-api.md` để có luồng đăng nhập lấy `accessToken` trước, vì **toàn bộ endpoint của user-service đều bắt buộc token** (không có endpoint public nào, kể cả xem profile người khác).

## 1. Tech stack liên quan đến client

| Thành phần | Công nghệ |
|---|---|
| Giao thức | REST/HTTP, JSON |
| Auth | JWT access token, Bearer scheme — bắt buộc trên **mọi** endpoint |
| Entry point cho mobile | **api-gateway** (`:8080`), KHÔNG gọi thẳng `user-service:8082` |
| Base URL (qua gateway) | `http://<gateway-host>:8080/api/v1/users` |
| Docs tương tác | `http://localhost:8082/swagger-ui.html` (chạy trực tiếp user-service khi dev) |

## 2. Response envelope chuẩn

Giống hệt `auth-service` — mọi response bọc trong `ApiResponse<T>` (`success`/`data`/`code`/`message`/`timestamp`, `code`/`message` vắng mặt khi thành công do `@JsonInclude(NON_NULL)`). Xem chi tiết ở mục 2 của `docs/auth-service-api.md`, dùng chung 1 model Dart `ApiResponse<T>` cho toàn app.

Riêng các endpoint trả **danh sách** (followers/following/blocked/muted), `data` là 1 object phân trang, KHÔNG phải mảng trần. Metadata nằm gọn trong `page`, không nằm ngang hàng `content`:

```json
{
  "success": true,
  "data": {
    "content": [ { "userId": 123, "displayName": "...", ... } ],
    "page": {
      "size": 20,
      "number": 0,        // page index, bắt đầu từ 0
      "totalElements": 42,
      "totalPages": 3
    }
  },
  "code": null,
  "message": null,
  "timestamp": "2026-08-02T10:00:00Z"
}
```

Chỉ có đúng 4 field trong `page`. Các field `first`/`last`/`numberOfElements`/`empty`/`sort`/`pageable` của `PageImpl` **không được trả về** — server serialize bằng `VIA_DTO` để không phơi cấu trúc nội bộ của Spring Data ra làm contract. Suy ra tương đương ở phía client: `last` = `number + 1 >= totalPages`, `empty` = `content.isEmpty`.

Gợi ý: viết 1 wrapper `PageResponse<T>` dùng chung cho các list này ở phía Dart (`content` + `page.{size,number,totalElements,totalPages}`). `video-service` trả về đúng format này, nên dùng chung được 1 wrapper.

`size` bị chặn trên ở **50**; xin lớn hơn thì bị kẹp xuống 50 chứ không báo lỗi. Không truyền thì mặc định 20.

**`content.length` có thể nhỏ hơn `page.size` mà vẫn CÒN trang tiếp theo.** Quan hệ (follow/block/mute) được lưu tách khỏi profile, nên một id trong trang có thể không còn profile tương ứng (user đã bị xoá mềm, hoặc profile chưa kịp tạo qua Kafka); server **bỏ qua** id đó thay vì trả lỗi cả trang. `page.totalElements` vẫn đếm đủ số quan hệ, nên phân trang không bị lệch. Hệ quả phía Flutter: **đừng dùng `content.isEmpty` hay `content.length < size` làm điều kiện dừng infinite scroll** — chỉ dựa vào `number + 1 >= totalPages`. Một trang trả ít item hơn mong đợi là bình thường, không phải hết dữ liệu.

## 3. Endpoints

Base path: `/api/v1/users`. **Mọi request đều bắt buộc** header `Authorization: Bearer <accessToken>` (cùng access token lấy từ `auth-service`, cùng luật `isValidAccessToken()` — token hết hạn/bị blacklist đều trả `401`).

### 3.1 `GET /me`
Lấy profile của chính mình. Trả `200 OK`.

Response `data` → `UserProfileResponse`:
```json
{
  "userId": 123456789012345,
  "displayName": "Minh Hùng",
  "bio": "Xin chào",
  "avatarUrl": "https://cdn.tiktok-clone.local/avatars/123.jpg",
  "followerCount": 10,
  "followingCount": 5
}
```

> Ngay sau khi `register` ở auth-service, profile được tạo **bất đồng bộ qua Kafka** (không đồng bộ ngay trong response của `/register`). Có thể có 1 khoảng trễ ngắn (thường vài trăm ms) mà `GET /me` trả `404 USER_PROFILE_NOT_FOUND`. Client nên **retry nhẹ** (1-2 lần, delay ngắn) nếu gặp lỗi này ngay sau khi đăng ký/đăng nhập lần đầu, thay vì coi là lỗi cứng.

### 3.2 `PATCH /me`
Cập nhật profile — **partial update thật sự** (giống PATCH chuẩn REST, không phải PUT trá hình). Trả `200 OK`.

Request (mọi field đều optional, `UpdateProfileRequest`):
```json
{
  "displayName": "Tên mới",   // optional, 1-100 ký tự, không được toàn khoảng trắng
  "bio": "Bio mới",            // optional, tối đa 500 ký tự
  "avatarUrl": "https://cdn.tiktok-clone.local/avatars/123.jpg"  // optional, tối đa 500 ký tự
}
```

**Quy tắc partial update — quan trọng, dễ làm sai phía client:**
- **Không gửi field** (field vắng mặt trong JSON, hoặc `null`) → giữ nguyên giá trị cũ trên server.
- **Gửi field = `""`** (chuỗi rỗng) → **xoá** giá trị (set về `null` trong DB). Áp dụng cho `bio` và `avatarUrl`.
- **`displayName` không thể xoá bằng chuỗi rỗng** — server từ chối whitespace-only bằng `VALIDATION_ERROR` (400). Chỉ có thể giữ nguyên (không gửi) hoặc thay bằng giá trị khác.

→ Vì vậy, khi build request body ở Flutter: **chỉ đưa key nào user thực sự chỉnh sửa vào JSON** (dùng `Map<String, dynamic>` build tay hoặc `@JsonKey(includeIfNull: false)` + loại bỏ field không đổi), tuyệt đối không serialize nguyên object rồi gửi full — nếu form có field trống mặc định (`""`), gửi nguyên request sẽ vô tình xoá `bio`/`avatarUrl` hiện có của user.

`avatarUrl` phải là URL **https** với host nằm trong allow-list phía server (CDN nội bộ) — client không tự nghĩ ra URL avatar, mà lấy URL trả về từ luồng upload ảnh (ngoài phạm vi user-service, xem mục 8). Gửi URL sai domain/scheme → `VALIDATION_ERROR` (400).

Response `data` → `UserProfileResponse` (giống mục 3.1, đã áp dụng thay đổi).

Lỗi: `VALIDATION_ERROR` (400).

### 3.3 `GET /{userId}`
Xem profile người khác. Trả `200 OK`.

Response `data` → `UserProfileResponse` (giống mục 3.1).

Lỗi: `USER_PROFILE_NOT_FOUND` (404) — xảy ra khi (a) user không tồn tại, **hoặc** (b) có quan hệ block giữa mình và `userId` đó (theo **cả 2 chiều** — mình block họ hoặc họ block mình). Server **cố tình** không phân biệt 2 trường hợp này (không trả `403` riêng) để không lộ thông tin "user này đã block bạn". Flutter nên coi `404` ở endpoint này là "không xem được profile" chung chung (ẩn nút, hiện placeholder "Người dùng không tồn tại"), không suy luận thêm là do bị block hay do sai id.

### 3.4 `POST /{userId}/follow`
Follow 1 user. Trả `201 Created`.

Response `data` → `FollowResponse`: `{ "followerId": ..., "followingId": ... }`.

Lỗi: `CANNOT_FOLLOW_SELF` (400 — tự follow chính mình), `USER_BLOCKED` (403 — giữa 2 bên đang có quan hệ block, theo cả 2 chiều), `USER_PROFILE_NOT_FOUND` (404), `ALREADY_FOLLOWING` (409).

`USER_PROFILE_NOT_FOUND` ở đây có **2 nguồn**, client cần phân biệt vì cách xử lý khác nhau:
- Profile của **người được follow** không tồn tại → hiển thị "người dùng không tồn tại" như mục 3.3.
- Profile của **chính người gọi** chưa tồn tại — xảy ra khi vừa đăng ký xong và `UserRegisteredEvent` chưa kịp tạo profile (xem mục 3.1). Triệu chứng: `GET /me` cũng trả 404 cùng lúc. Xử lý: retry nhẹ như mục 3.1, đừng báo cho user là "người kia không tồn tại".

Cách phân biệt rẻ nhất: nếu `GET /me` đang chạy được bình thường thì 404 chắc chắn đến từ target.

### 3.5 `DELETE /{userId}/follow`
Unfollow. Trả `200 OK`, `data` rỗng/null.

Lỗi: `NOT_FOLLOWING` (404), `CONCURRENT_MODIFICATION` (409 — bấm unfollow 2 lần sát nhau/2 thiết bị cùng lúc; retry 1 lần là hết, xem mục 5).

### 3.6 `GET /{userId}/followers`
Danh sách follower của `userId`. Query param phân trang chuẩn Spring: `?page=0&size=20&sort=field,dir`. Trả `200 OK`, `data` → `Page<UserProfileResponse>` (xem mục 2).

Lỗi: `USER_PROFILE_NOT_FOUND` (404) — áp dụng cùng luật ẩn-do-block như mục 3.3 (không xem được followers của người đã block/bị block mình).

### 3.7 `GET /{userId}/following`
Danh sách người `userId` đang follow. Tham số & lỗi giống hệt mục 3.6.

### 3.8 `POST /{userId}/block`
Chặn 1 user. Trả `201 Created`.

Response `data` → `BlockResponse`: `{ "blockerId": ..., "blockedId": ... }`.

Lỗi: `CANNOT_BLOCK_SELF` (400), `USER_PROFILE_NOT_FOUND` (404 — người bị block không tồn tại), `ALREADY_BLOCKED` (409).

**Tác dụng phụ khi block (server tự xử lý, client không cần gọi thêm API):**
- Quan hệ follow giữa 2 bên (theo cả 2 chiều — mình follow họ hoặc họ follow mình) bị **huỷ tự động**, counter `followerCount`/`followingCount` được cập nhật lại.
- Từ lúc này, `GET /{userId}` và danh sách followers/following của nhau đều trả `404 USER_PROFILE_NOT_FOUND` cho cả 2 phía (xem mục 3.3).
- **Không** tự động mute — nếu muốn ẩn cả thông báo/tin nhắn thì phải gọi `mute` riêng (xem mục 3.11), 2 cơ chế hoàn toàn độc lập.

### 3.9 `DELETE /{userId}/block`
Bỏ chặn. Trả `200 OK`, `data` rỗng/null.

Lỗi: `NOT_BLOCKED` (404), `CONCURRENT_MODIFICATION` (409 — xem mục 3.5).

> Unblock **không** khôi phục lại quan hệ follow đã bị huỷ lúc block. Nếu muốn follow lại thì phải gọi `POST /{userId}/follow` lần nữa — UI nên nói rõ điều này để user không tưởng là bug.

### 3.10 `GET /me/blocked`
Danh sách user mình đang block. Phân trang giống mục 3.6. Trả `200 OK`, `data` → `Page<UserProfileResponse>`.

### 3.11 `POST /{userId}/mute`
Mute 1 user (ẩn nội dung của họ khỏi feed/thông báo — tuỳ service khác dùng thông tin này, user-service chỉ lưu trạng thái). Trả `201 Created`.

Response `data` → `MuteResponse`: `{ "muterId": ..., "mutedId": ... }`.

Lỗi: `CANNOT_MUTE_SELF` (400), `USER_PROFILE_NOT_FOUND` (404 — người bị mute không tồn tại), `ALREADY_MUTED` (409).

> **Mute KHÔNG kiểm tra block, hoàn toàn độc lập với follow/block** (khác với `follow`, vốn bị chặn nếu có block). Có thể mute 1 người đang block mình (hoặc mình đang block họ) mà không lỗi — đây là thiết kế có chủ đích: (1) không dùng lỗi mute để dò xem ai đã block ai, (2) cho phép "hạ cấp" từ block xuống mute sau khi unblock. Flutter không nên giả định mute/block đồng bộ trạng thái với nhau — hiển thị UI 2 toggle độc lập.

### 3.12 `DELETE /{userId}/mute`
Bỏ mute. Trả `200 OK`, `data` rỗng/null.

Lỗi: `NOT_MUTED` (404), `CONCURRENT_MODIFICATION` (409 — xem mục 3.5).

### 3.13 `GET /me/muted`
Danh sách user mình đang mute. Phân trang giống mục 3.6. Trả `200 OK`, `data` → `Page<UserProfileResponse>`.

## 4. Enums

Không có enum nào ở user-service (không có trạng thái "visibility", "follow status"... — follow/block/mute chỉ là quan hệ có/không, xoá mềm qua `deletedAt`). Enum `UserRole`/`UserStatus` thuộc về `auth-service`, không lặp lại ở đây.

## 5. Bảng mã lỗi (`code`) đầy đủ

| `code` | HTTP status | Khi nào xảy ra |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Body không đúng ràng buộc (`displayName`/`bio`/`avatarUrl` sai định dạng/độ dài) |
| `CANNOT_FOLLOW_SELF` | 400 | Gọi `follow` lên chính `userId` của mình |
| `CANNOT_BLOCK_SELF` | 400 | Gọi `block` lên chính mình |
| `CANNOT_MUTE_SELF` | 400 | Gọi `mute` lên chính mình |
| `USER_BLOCKED` | 403 | `follow` thất bại vì đang có quan hệ block (2 chiều) giữa 2 bên |
| `USER_PROFILE_NOT_FOUND` | 404 | User không tồn tại, bị ẩn do có block giữa 2 bên (server cố tình gộp 2 case, xem mục 3.3), **hoặc** profile của chính người gọi chưa được tạo xong qua Kafka (xem mục 3.4) |
| `ALREADY_FOLLOWING` | 409 | Follow lại người đã follow |
| `ALREADY_BLOCKED` | 409 | Block lại người đã block |
| `ALREADY_MUTED` | 409 | Mute lại người đã mute |
| `NOT_FOLLOWING` | 404 | Unfollow người chưa follow |
| `NOT_BLOCKED` | 404 | Unblock người chưa block |
| `NOT_MUTED` | 404 | Unmute người chưa mute |
| `CONCURRENT_MODIFICATION` | 409 | Hai request sửa cùng 1 bản ghi đúng lúc (2 lần bấm unfollow/unblock/unmute sát nhau, hoặc 2 thiết bị cùng thao tác). **Mã 409 duy nhất nên retry** — đọc lại trạng thái rồi thử lại 1 lần; các mã `ALREADY_*` là lỗi nghiệp vụ, retry vô ích |
| `INTERNAL_ERROR` | 500 | Lỗi không xác định |

Ngoài ra `401 Unauthorized` (không có `code` riêng của user-service, đến từ `security-lib`/gateway) xảy ra ở **mọi** endpoint nếu thiếu/hết hạn/token bị blacklist — xử lý giống hệt cách auth-service doc đã mô tả (thử `/refresh` 1 lần rồi mới logout local).

## 6. Rate limiting

- Chỉ áp dụng rate-limit **ở tầng gateway** (`:8080`, theo IP — 20 request/giây, burst 40, dùng chung cho toàn bộ API, không riêng user-service).
- **Không có** rate-limit riêng theo tài khoản ở user-service (khác `auth-service` — không có giới hạn kiểu "quá N lần follow/block trong M phút").

## 7. Những điều KHÔNG nên làm phía Flutter

- Không serialize nguyên object `UpdateProfileRequest` (kèm field rỗng mặc định của form) khi gọi `PATCH /me` — sẽ vô tình xoá `bio`/`avatarUrl` hiện có do luật "chuỗi rỗng = xoá field" (xem mục 3.2). Chỉ gửi field user thực sự đã sửa.
- Không tự suy luận "user này đã chặn tôi" từ mã lỗi `404 USER_PROFILE_NOT_FOUND` — server cố tình không phân biệt "không tồn tại" và "bị chặn", UI chỉ nên hiển thị chung "không thể xem".
- Không giả định `block` tự động `mute` hoặc ngược lại — 2 trạng thái độc lập, phải gọi API riêng và hiển thị 2 toggle riêng trên UI.
- Không tự build URL avatar phía client rồi gửi thẳng lên `PATCH /me` — chỉ dùng URL trả về từ luồng upload ảnh thật (ngoài phạm vi user-service), vì server chặn theo allow-list host + bắt buộc `https`.
- Không coi `404` ở `GET /me` ngay sau khi vừa đăng ký/login lần đầu là lỗi cứng — profile được tạo bất đồng bộ qua Kafka, nên retry nhẹ trước khi báo lỗi cho user (xem mục 3.1).
- Không dừng infinite scroll khi `content` ngắn hơn `size` — dùng `number + 1 >= totalPages`, vì server có thể bỏ qua vài id không còn profile trong trang (xem mục 2).
- Không coi mọi `409` như nhau: `CONCURRENT_MODIFICATION` nên retry 1 lần, còn `ALREADY_FOLLOWING`/`ALREADY_BLOCKED`/`ALREADY_MUTED` thì phải đồng bộ lại UI chứ retry không giải quyết gì.
- Không cho user bấm liên tục nút follow/unfollow (debounce nút sau khi bấm) — 2 request song song sẽ tạo `CONCURRENT_MODIFICATION` và counter hiển thị nhấp nháy.
- Không gọi thẳng `user-service:8082` từ production build — luôn qua gateway `:8080`.
