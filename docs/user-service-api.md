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
  "followingCount": 5,
  "privateAccount": false,
  "displayNameChangeableAt": null
}
```

`displayNameChangeableAt`: khi nào được đổi `displayName` lần tới (mục 3.2). **Chỉ chủ tài khoản thấy**, trên profile của chính mình (`GET /me`, `PATCH /me`, `POST /me/avatar`, và `GET /{userId}` / `GET /by-username/{username}` khi người gọi là chủ); `null` ở mọi chỗ khác, và `null` với chủ khi đổi được ngay.

`privateAccount = true`: tài khoản riêng tư — chỉ người đã được duyệt follow mới xem được video, danh sách followers/following và tab Reposts (mục 3.16). Profile và các con số (followers, following, likes, số video) vẫn công khai như TikTok. Field này có trên **mọi** `UserProfileResponse` (mục 3.3, 3.3b, danh sách, tìm kiếm).

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
- **`displayName` chỉ đổi được 1 lần mỗi 7 ngày.** Đổi lần nữa khi chưa đủ 7 ngày → `429 DISPLAY_NAME_CHANGE_TOO_SOON`, và **cả request bị từ chối** (bio/avatar gửi kèm cũng không được lưu). Gửi lại đúng tên hiện tại không tính là đổi, kể cả khi chỉ khác khoảng trắng ở đầu/cuối (`"Alice "` và `"Alice"` là cùng một tên); tên mới được lưu sau khi bỏ khoảng trắng đó. Tên ban đầu (copy từ username lúc đăng ký) không tính, nên lần đổi đầu tiên luôn được phép. `bio`/`avatarUrl` không bị giới hạn. Đọc `displayNameChangeableAt` (mục 3.1) để khoá ô tên kèm ngày mở lại.

→ Vì vậy, khi build request body ở Flutter: **chỉ đưa key nào user thực sự chỉnh sửa vào JSON** (dùng `Map<String, dynamic>` build tay hoặc `@JsonKey(includeIfNull: false)` + loại bỏ field không đổi), tuyệt đối không serialize nguyên object rồi gửi full — nếu form có field trống mặc định (`""`), gửi nguyên request sẽ vô tình xoá `bio`/`avatarUrl` hiện có của user.

`avatarUrl` phải là URL **https** với host nằm trong allow-list phía server (CDN nội bộ) — client không tự nghĩ ra URL avatar. Gửi URL sai domain/scheme → `VALIDATION_ERROR` (400). Muốn đổi ảnh đại diện thì **không dùng field này**, mà upload file qua `POST /me/avatar` (mục 3.2b) — server tự lưu và tự set `avatarUrl`.

Response `data` → `UserProfileResponse` (giống mục 3.1, đã áp dụng thay đổi).

#### Avatar của tài khoản Google/Facebook

Đăng ký/đăng nhập bằng Google hoặc Facebook thì profile tự có ảnh của provider, client không phải làm gì:

1. auth-service gửi kèm ảnh provider trong `UserRegisteredEvent` → profile mới có `avatarUrl` ngay (URL của Google/Facebook).
2. Mỗi lần đăng nhập social, auth-service phát `SocialAvatarDiscoveredEvent` (topic `auth.social-avatar-events`). media-worker tải ảnh về MinIO (`avatars/{userId}.jpg`) rồi phát `AvatarMirroredEvent` (topic `media.avatar-events`).
3. user-service đổi `avatarUrl` sang bản trong MinIO — ví dụ `http://localhost:9000/video-media/avatars/123.jpg`. Cần bước này vì URL ảnh Facebook hết hạn sau vài ngày.

Bước 3 **chỉ** ghi đè khi `avatarUrl` đang trống hoặc vẫn đúng URL provider vừa được sao chép. Ảnh user tự đặt qua `PATCH /me` không bao giờ bị thay. Tài khoản social tạo trước tính năng này (`avatarUrl` null) được điền ảnh ở lần đăng nhập kế tiếp.

Client hiển thị đúng URL server trả về, không suy đoán host. Next.js phải khai báo host trong `images.remotePatterns` (`lh3.googleusercontent.com`, `platform-lookaside.fbsbx.com`, và origin MinIO) mới render được qua `next/image`.

Lỗi: `VALIDATION_ERROR` (400), `DISPLAY_NAME_CHANGE_TOO_SOON` (429).

### 3.2b `POST /me/avatar`

Upload ảnh đại diện. `multipart/form-data`, đúng một part tên `file`.

- Content type nhận: `image/jpeg`, `image/png`, `image/webp`. SVG bị từ chối (trình duyệt chạy script trong SVG).
- Tối đa 5 MB (`AVATAR_MAX_BYTES`). Quá cỡ → `INVALID_AVATAR` (400 nếu qua được servlet, 413 nếu bị chặn ở giới hạn multipart).
- Sai định dạng / part rỗng → `INVALID_AVATAR` (400).

Server ghi file vào MinIO tại `avatars/{userId}.jpg` (đúng key media-worker dùng khi mirror ảnh social, nên một user chỉ có một object ảnh), rồi tự set `avatarUrl` và trả về **cả profile** — `UserProfileResponse` như mục 3.1. Client refresh từ response này giống hệt sau `PATCH /me`.

URL trả về có đuôi `?v={epochSeconds}`, ví dụ `http://localhost:9000/video-media/avatars/123.jpg?v=1756000000`. Key cố định nên nếu thiếu query này, lần upload thứ hai trả đúng URL cũ và trình duyệt/CDN vẫn phục vụ ảnh cũ. **Client phải lưu nguyên URL kèm query**, không cắt bỏ.

Luồng đúng ở client: chọn ảnh → chỉ preview local (object URL / file path), **chưa gọi API** → khi user bấm Save mới `POST /me/avatar`; upload lỗi thì huỷ luôn cả lần save đó, đừng để đổi được tên mà ảnh thì không.

### 3.3 `GET /{userId}`
Xem profile người khác. Trả `200 OK`.

Response `data` → `UserProfileResponse` (giống mục 3.1).

Lỗi: `USER_PROFILE_NOT_FOUND` (404) — xảy ra khi (a) user không tồn tại, **hoặc** (b) có quan hệ block giữa mình và `userId` đó (theo **cả 2 chiều** — mình block họ hoặc họ block mình). Server **cố tình** không phân biệt 2 trường hợp này (không trả `403` riêng) để không lộ thông tin "user này đã block bạn". Flutter nên coi `404` ở endpoint này là "không xem được profile" chung chung (ẩn nút, hiện placeholder "Người dùng không tồn tại"), không suy luận thêm là do bị block hay do sai id.

### 3.3b `GET /?ids=1,2,3`
Lấy nhiều profile trong **1 request**. Trả `200 OK`, `data` → **mảng** `UserProfileResponse` (không phải `Page`).

Dành cho màn hình đã có sẵn danh sách `userId` mà chưa có profile: feed video (`VideoResponse` chỉ trả `userId`), danh sách comment. Gọi `GET /{userId}` 20 lần cho 1 trang feed là dùng sai.

- Tối đa **100** id mỗi request → quá thì `TOO_MANY_PROFILE_IDS` (400).
- Id trùng bị gộp.
- Thứ tự trả về **đúng thứ tự gửi lên**.

> **Mảng trả về có thể NGẮN HƠN `ids` gửi lên, và đó không phải lỗi.** Id không tồn tại, hoặc có quan hệ block giữa mình và id đó (cả 2 chiều, giống mục 3.3), sẽ **bị bỏ khỏi kết quả** chứ không làm hỏng cả trang. Client phải **dựng `Map<userId, profile>` từ kết quả rồi tra theo id**, tuyệt đối không ghép theo vị trí (`ids[i]` ↔ `data[i]`) — lệch một phần tử là gán nhầm avatar/tên sang người khác. Id không tra được thì hiện placeholder.

### 3.4 `POST /{userId}/follow`
Follow 1 user. Trả `201 Created`.

Response `data` → `FollowResponse`: `{ "followerId": ..., "followingId": ..., "status": "FOLLOWING" }`.

`status` cho biết chuyện gì đã xảy ra — cả hai đều trả `201`:
- `FOLLOWING` — đã follow ngay (tài khoản công khai).
- `REQUESTED` — tài khoản **riêng tư**: server tạo **lời mời follow**, chưa follow. Chủ tài khoản duyệt thì mới thành follow (mục 3.16). Nút đổi thành "Requested"; bấm lại (`DELETE`, mục 3.5) là rút lời mời.

Lỗi: `CANNOT_FOLLOW_SELF` (400 — tự follow chính mình), `USER_BLOCKED` (403 — giữa 2 bên đang có quan hệ block, theo cả 2 chiều), `USER_PROFILE_NOT_FOUND` (404), `ALREADY_FOLLOWING` (409), `ALREADY_REQUESTED` (409 — đã có lời mời đang chờ duyệt).

`USER_PROFILE_NOT_FOUND` ở đây có **2 nguồn**, client cần phân biệt vì cách xử lý khác nhau:
- Profile của **người được follow** không tồn tại → hiển thị "người dùng không tồn tại" như mục 3.3.
- Profile của **chính người gọi** chưa tồn tại — xảy ra khi vừa đăng ký xong và `UserRegisteredEvent` chưa kịp tạo profile (xem mục 3.1). Triệu chứng: `GET /me` cũng trả 404 cùng lúc. Xử lý: retry nhẹ như mục 3.1, đừng báo cho user là "người kia không tồn tại".

Cách phân biệt rẻ nhất: nếu `GET /me` đang chạy được bình thường thì 404 chắc chắn đến từ target.

### 3.5 `DELETE /{userId}/follow`
Unfollow. Trả `200 OK`, `data` rỗng/null. Chưa follow nhưng đang có lời mời chờ duyệt (tài khoản riêng tư) thì endpoint này **rút lời mời** — cùng một nút trên UI, không phát thông báo gì.

Lỗi: `NOT_FOLLOWING` (404 — không follow, cũng không có lời mời nào đang chờ), `CONCURRENT_MODIFICATION` (409 — bấm unfollow 2 lần sát nhau/2 thiết bị cùng lúc; retry 1 lần là hết, xem mục 5).

### 3.6 `GET /{userId}/followers`
Danh sách follower của `userId`. Query param phân trang chuẩn Spring: `?page=0&size=20&sort=field,dir`. Trả `200 OK`, `data` → `Page<UserProfileResponse>` (xem mục 2).

Lỗi: `USER_PROFILE_NOT_FOUND` (404) — áp dụng cùng luật ẩn-do-block như mục 3.3 (không xem được followers của người đã block/bị block mình). `PRIVATE_ACCOUNT` (403) — tài khoản riêng tư mà người gọi không phải chủ, cũng không phải follower. Là 403 chứ không phải 404 vì profile đã công khai việc tài khoản riêng tư; UI hiện "This account is private".

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

### 3.14 `GET /me/settings`

Cài đặt tài khoản (phần đứng sau trang `/setting` của web). **Bắt buộc token.** Trả `200 OK`, `data` → `UserSettingsResponse`:

```json
{
  "commentAudience": "EVERYONE",
  "dmAudience": "EVERYONE",
  "screenTimeLimitMinutes": null,
  "sleepHoursStart": null,
  "sleepHoursEnd": null,
  "weeklyScreenTimeDigest": true,
  "privateAccount": false
}
```

> **Không bao giờ trả `404`.** Tài khoản chưa từng đổi cài đặt nào thì **không có row trong DB** và endpoint trả đúng bộ giá trị mặc định ở trên — khác `GET /me` (mục 3.1), ở đây client không phải retry và không phải xử lý case "chưa có cài đặt". Đọc cũng **không** tạo row.

- Đây là một **object phẳng theo key**, không phải cây section/group mà trang settings vẽ ra. Layout là việc của client; server chỉ giữ giá trị.
- `null` ở ba field screen-time nghĩa là **tắt** — không có cờ enable riêng, và `screenTimeLimitMinutes = 0` là lỗi validate chứ không phải cách thứ hai để nói "tắt".
- `sleepHoursStart`/`sleepHoursEnd` là **giờ treo tường, không mang timezone** (`"22:30"`, định dạng `HH:mm`). Không tài khoản nào trên hệ thống này có timezone, nên hai field đó chỉ có thể hiểu là "22:30 theo giờ của chính máy người dùng" và **client là phía enforce**. Server không bao giờ hẹn được lịch từ hai field này.
- Bốn field screen-time hiện **chỉ được lưu và trả lại, không có gì đọc chúng** ở server. Đó là trạng thái cố ý: giới hạn thời gian dùng app do client enforce, việc của server là sync cài đặt đó giữa các thiết bị.
- `commentAudience` / `dmAudience` **có** hiệu lực thật, do interaction-service và chat-service enforce — xem mục 3.15.
- `privateAccount` được lưu trên **profile** chứ không phải bảng settings (mọi người xem đều cần nó — header profile, nút Follow), nhưng bật/tắt ở đây vì công tắc nằm trên trang `/setting`. Chưa có profile thì trả `false`.

### 3.15 `PATCH /me/settings`

Sửa cài đặt. **Bắt buộc token.** Trả `200 OK`, `data` → `UserSettingsResponse` (toàn bộ, sau khi đã áp thay đổi).

Request (mọi field optional, `UpdateUserSettingsRequest`):
```json
{
  "commentAudience": "FRIENDS",
  "dmAudience": "NO_ONE",
  "screenTimeLimitMinutes": 60,
  "sleepHoursStart": "22:30",
  "sleepHoursEnd": "07:00",
  "weeklyScreenTimeDigest": false,
  "privateAccount": true
}
```

**Quy tắc partial update — KHÁC `PATCH /me` (mục 3.2), đừng áp dụng lẫn nhau:**

| Client gửi | Kết quả |
|---|---|
| **Không có key** trong JSON | Giữ nguyên giá trị cũ |
| Key = `null`, với 3 field **nullable** (`screenTimeLimitMinutes`, `sleepHoursStart`, `sleepHoursEnd`) | **Xoá** giá trị (về `null` = tắt) |
| Key = `null`, với `commentAudience` / `dmAudience` / `weeklyScreenTimeDigest` / `privateAccount` | `VALIDATION_ERROR` (400) — bốn cột này NOT NULL, không có `null` để xoá về |
| Chuỗi rỗng `""` | **Không** phải cách xoá field ở endpoint này (khác mục 3.2) — sẽ là `VALIDATION_ERROR` |

→ Ở Flutter: build body bằng `Map<String, dynamic>` chỉ chứa key user vừa đổi. Muốn tắt giới hạn thời gian thì gửi đúng `{"screenTimeLimitMinutes": null}`, **phải có key**, đừng bỏ trống.

Validate:

- `commentAudience`, `dmAudience` ∈ `EVERYONE` | `FRIENDS` | `NO_ONE` → sai giá trị là `VALIDATION_ERROR` (400).
- `screenTimeLimitMinutes` ∈ `[10, 1440]` hoặc `null`.
- `sleepHoursStart`/`sleepHoursEnd`: **cùng có hoặc cùng không** — set một cái và để cái kia `null` là `VALIDATION_ERROR`, kể cả khi cái kia đang có sẵn trong DB (luật được kiểm trên **kết quả sau khi merge**, không phải trên request). Hai đầu **bằng nhau** là lỗi; `start > end` thì **hợp lệ** (khung giờ vắt qua nửa đêm là chuyện thường).
- Định dạng giờ là `HH:mm` đúng như response trả về — gửi `"22:30:00"` hoặc `"10:30 PM"` sẽ bị body parser từ chối với `400 BAD_REQUEST` (không phải `VALIDATION_ERROR`).

`CONCURRENT_MODIFICATION` (409) nếu hai thiết bị ghi cùng lúc — đọc lại rồi thử lại một lần, giống mục 3.5.

**Hai field có hiệu lực thật, và chúng chỉ chặn lúc bắt đầu:**

- `commentAudience` do **interaction-service** enforce khi post comment: `NO_ONE` chặn tất cả, `FRIENDS` chỉ cho người follow lẫn nhau → `COMMENTS_NOT_ALLOWED` (403). Cờ tắt comment của **từng video** vẫn thắng cài đặt tài khoản: video đã tắt comment thì tắt hẳn, và trả mã riêng `COMMENTS_DISABLED`. `NO_ONE` chặn cả chủ video — mọi video của tài khoản đó coi như đã tắt comment (ẩn ô nhập, ẩn số comment, ẩn danh sách). Chủ video chỉ được miễn `FRIENDS`.
- `dmAudience` do **chat-service** enforce **lúc mở cuộc hội thoại**, không phải mỗi tin nhắn → `MESSAGING_NOT_ALLOWED` (403). Hội thoại đã mở trước khi người kia siết cài đặt thì vẫn gửi được (giống "người đang follow không bị ảnh hưởng" của private account). Chỉ cài đặt của **người nhận** được xét: người gửi dù đặt `NO_ONE` vẫn chủ động mở được hội thoại.
- Đổi `commentAudience` có thể mất tới ~3 giây mới có tác dụng (kể cả `accountCommentsOff` trên video): video-service cache cài đặt của chủ video trong Redis với TTL ngắn (`USER_SETTINGS_CACHE_TTL`, mặc định 3s). Không có event invalidate.

**`privateAccount`** — bật thì từ lúc đó chỉ người được duyệt mới follow được (mục 3.4, 3.16) và xem được video, danh sách followers/following, tab Reposts. **Follower hiện có giữ nguyên.** Gửi đúng giá trị đang có là no-op, không phát event. Tắt (về công khai) thì **mọi lời mời đang chờ được duyệt hết** trong cùng request — không còn gì chặn việc follow, và những người đó đã xin rồi. Chưa có profile → `USER_PROFILE_NOT_FOUND` (404). Video của tài khoản rời khỏi For You, trending, feed mới nhất và tìm kiếm trong vòng vài chục giây (video-service đánh dấu lại từng video, 100 video mỗi 5 giây).

### 3.16 Lời mời follow — `GET /me/follow-requests`, `POST /me/follow-requests/{requesterId}/approve`, `DELETE /me/follow-requests/{requesterId}`

Phía **chủ tài khoản riêng tư**. **Bắt buộc token.**

| Endpoint | Trả về | Lỗi |
|---|---|---|
| `GET /me/follow-requests` | `200`, `data` → `Page<UserProfileResponse>` của người xin follow, **mới nhất trước**. Phân trang giống mục 3.6 | — |
| `POST /me/follow-requests/{requesterId}/approve` | `200`, `data` → `FollowResponse` với `status: "FOLLOWING"` | `FOLLOW_REQUEST_NOT_FOUND` (404) |
| `DELETE /me/follow-requests/{requesterId}` | `200`, `data` null. **Im lặng** — người xin không được báo | `FOLLOW_REQUEST_NOT_FOUND` (404) |

- Duyệt tạo follow y như follow thường (counter, event). Người xin nhận thông báo `FOLLOW_REQUEST_ACCEPTED`; chủ tài khoản **không** nhận `NEW_FOLLOWER` (chính họ vừa bấm duyệt).
- Có lời mời mới thì chủ tài khoản nhận thông báo `FOLLOW_REQUEST`. Lời mời bị rút hoặc bị từ chối **vẫn còn thông báo trong inbox** — danh sách ở đây mới là nguồn sự thật; bấm vào thông báo cũ thì mở danh sách này.
- Block (bất kể chiều nào) xoá luôn lời mời giữa hai bên.
- Bị từ chối rồi vẫn xin lại được — đó là một lời mời mới.

### 3.17 `GET /{userId}/friendship`

Quan hệ giữa người gọi và `userId` — đủ cho nút Follow trong **một** lần gọi. **Bắt buộc token.** Trả `200 OK`, `data` → `FriendshipResponse`:

```json
{
  "userId": 42,
  "friends": false,
  "following": false,
  "requested": true,
  "privateAccount": true
}
```

- `following` — mình đang follow người này. `requested` — mình có lời mời đang chờ duyệt. `friends` — follow **lẫn nhau**. `privateAccount` — tài khoản kia riêng tư.
- Nút Follow: `following` → "Following"; `requested` → "Requested"; còn lại → "Follow". Không cần tự dò danh sách following của mình nữa.
- Không có lỗi `404`: id không tồn tại chỉ trả toàn `false`.

## 4. Enums

| Enum | Giá trị | Dùng ở đâu |
|---|---|---|
| `SettingsAudience` | `EVERYONE`, `FRIENDS`, `NO_ONE` | `commentAudience` và `dmAudience` ở mục 3.14 / 3.15. `FRIENDS` nghĩa là **follow lẫn nhau** (mutual), không phải "người tôi follow" |
| `FollowResponse.status` | `FOLLOWING`, `REQUESTED` | `status` ở mục 3.4 và 3.16 |

Ngoài ra không có enum nào khác ở user-service — follow/block/mute và lời mời follow đều là quan hệ có/không, xoá mềm qua `deletedAt`. Enum `UserRole`/`UserStatus` thuộc về `auth-service`, không lặp lại ở đây.

## 5. Bảng mã lỗi (`code`) đầy đủ

| `code` | HTTP status | Khi nào xảy ra |
|---|---|---|
| `INVALID_AVATAR` | 400 / 413 | File upload ở `POST /me/avatar` sai định dạng, rỗng, hoặc quá 5 MB |
| `VALIDATION_ERROR` | 400 | Body không đúng ràng buộc (`displayName`/`bio`/`avatarUrl` sai định dạng/độ dài); ở mục 3.15: audience sai giá trị, `screenTimeLimitMinutes` ngoài `[10, 1440]`, `null` trên field NOT NULL, hoặc khung giờ ngủ thiếu một đầu / hai đầu bằng nhau |
| `BAD_REQUEST` | 400 | Body không parse được — ví dụ giờ ngủ không đúng định dạng `HH:mm` (mục 3.15). Khác `VALIDATION_ERROR` ở chỗ request chưa bao giờ thành object để validate |
| `TOO_MANY_PROFILE_IDS` | 400 | Gửi quá 100 id cho `GET /?ids=` (mục 3.3b). Chia nhỏ theo trang, đừng gộp cả feed vào 1 lần gọi |
| `DISPLAY_NAME_CHANGE_TOO_SOON` | 429 | `PATCH /me` đổi `displayName` khi chưa đủ 7 ngày từ lần đổi trước (mục 3.2). Retry vô ích cho tới `displayNameChangeableAt` |
| `CANNOT_FOLLOW_SELF` | 400 | Gọi `follow` lên chính `userId` của mình |
| `CANNOT_BLOCK_SELF` | 400 | Gọi `block` lên chính mình |
| `CANNOT_MUTE_SELF` | 400 | Gọi `mute` lên chính mình |
| `USER_BLOCKED` | 403 | `follow` thất bại vì đang có quan hệ block (2 chiều) giữa 2 bên |
| `PRIVATE_ACCOUNT` | 403 | Xem danh sách followers/following của tài khoản riêng tư mà không phải chủ hay follower (mục 3.6) |
| `USER_PROFILE_NOT_FOUND` | 404 | User không tồn tại, bị ẩn do có block giữa 2 bên (server cố tình gộp 2 case, xem mục 3.3), **hoặc** profile của chính người gọi chưa được tạo xong qua Kafka (xem mục 3.4) |
| `ALREADY_FOLLOWING` | 409 | Follow lại người đã follow |
| `ALREADY_REQUESTED` | 409 | Follow tài khoản riêng tư khi đã có lời mời đang chờ (mục 3.4) |
| `ALREADY_BLOCKED` | 409 | Block lại người đã block |
| `ALREADY_MUTED` | 409 | Mute lại người đã mute |
| `NOT_FOLLOWING` | 404 | Unfollow người chưa follow và cũng không có lời mời đang chờ |
| `FOLLOW_REQUEST_NOT_FOUND` | 404 | Duyệt/từ chối khi không có lời mời nào đang chờ từ người đó (mục 3.16) |
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
- Không tự build URL avatar phía client rồi gửi thẳng lên `PATCH /me` — server chặn theo allow-list host + bắt buộc `https`. Đổi ảnh thì upload file qua `POST /me/avatar` (mục 3.2b) và dùng `avatarUrl` trong response.
- Không coi `404` ở `GET /me` ngay sau khi vừa đăng ký/login lần đầu là lỗi cứng — profile được tạo bất đồng bộ qua Kafka, nên retry nhẹ trước khi báo lỗi cho user (xem mục 3.1).
- Không dừng infinite scroll khi `content` ngắn hơn `size` — dùng `number + 1 >= totalPages`, vì server có thể bỏ qua vài id không còn profile trong trang (xem mục 2).
- Không coi mọi `409` như nhau: `CONCURRENT_MODIFICATION` nên retry 1 lần, còn `ALREADY_FOLLOWING`/`ALREADY_BLOCKED`/`ALREADY_MUTED` thì phải đồng bộ lại UI chứ retry không giải quyết gì.
- Không cho user bấm liên tục nút follow/unfollow (debounce nút sau khi bấm) — 2 request song song sẽ tạo `CONCURRENT_MODIFICATION` và counter hiển thị nhấp nháy.
- Không dùng luật "chuỗi rỗng = xoá field" của `PATCH /me` cho `PATCH /me/settings` — ở endpoint settings, xoá một field nullable là gửi **đúng `null`** (và phải có key); chuỗi rỗng là lỗi validate. Hai endpoint đứng cạnh nhau nhưng cố ý khác nhau (mục 3.15).
- Không coi `GET /me/settings` trả mặc định là "chưa load xong" rồi retry — tài khoản chưa đổi cài đặt nào thì đó **là** câu trả lời đúng, không có row nào cả (mục 3.14).
- Không kỳ vọng đổi `commentAudience` có hiệu lực ngay lập tức trên mọi video — có cache TTL ngắn ở video-service; nếu UI vừa đổi vừa test thì chờ vài chục giây (mục 3.15).
- Không gọi thẳng `user-service:8082` từ production build — luôn qua gateway `:8080`.

## 8. Sự kiện Kafka (Events)

Phần này cho người tích hợp backend, không phải client mobile. `user-service` vừa phát vừa tiêu thụ event. Bảng đầy đủ toàn hệ thống: `docs/ARCHITECTURE.md` §5c.

### Phát (producer) — outbox

Ghi vào bảng `outbox_events` trong **cùng transaction** với thay đổi; `OutboxPublisher` poll mỗi 5s và chỉ đánh dấu published sau khi broker ack. Mỗi `aggregate_type` một topic, mỗi topic đúng một loại event — không có header `eventType`.

| Topic | Event | Key | Payload | Khi nào |
|---|---|---|---|---|
| `user.follow-events` | `UserFollowChangedEvent` | `followingId` | `eventId, occurredAt, followerId, followingId, followed, requestApproved` | Follow / unfollow / block cắt follow; duyệt lời mời (`requestApproved = true`). Event cũ không có `requestApproved` → đọc là `false` |
| `user.follow-request-events` | `FollowRequestedEvent` | `targetId` | `eventId, occurredAt, requesterId, targetId` | Follow tài khoản riêng tư. Rút hay từ chối **không** phát gì |
| `user.privacy-events` | `UserPrivacyChangedEvent` | `userId` | `eventId, occurredAt, userId, privateAccount` | `privateAccount` ở mục 3.15 thật sự đổi giá trị |

Consumer: `user.follow-events` → notification-service (`NEW_FOLLOWER` / `FOLLOW_REQUEST_ACCEPTED`) và chat-service (counter realtime); `user.follow-request-events` → notification-service (`FOLLOW_REQUEST`); `user.privacy-events` → video-service (collection `owner_privacy` và đánh dấu lại video).

### Tiêu thụ (consumer)

| Topic | Event | Payload | Consumer & tác dụng |
|---|---|---|---|
| `auth.user-events` | `UserRegisteredEvent` | `eventId, occurredAt, userId, username, email, avatarUrl` | `UserRegisteredEventConsumer` → tạo `UserProfile` (`displayName = username`, `avatarUrl` = ảnh provider nếu có). **Claim `eventId` vào bảng `inbox_events` TRƯỚC khi tạo** (`tryClaim` = `INSERT ... ON CONFLICT DO NOTHING`) — hai redelivery đua nhau chỉ một cái INSERT thắng, không tạo profile đôi. Đây là độ trễ khiến `GET /me` trả `404` ngay sau register (mục 3.1). Profile khác còn giữ đúng handle này thì bị gỡ handle, như ở `auth.username-events` |
| `media.avatar-events` | `AvatarMirroredEvent` | `eventId, occurredAt, userId, sourceUrl, avatarUrl` | `AvatarMirroredEventConsumer` → `applyMirroredAvatar`: đổi `avatarUrl` sang bản MinIO **chỉ khi** field đang trống hoặc đúng bằng `sourceUrl`. Ảnh user tự đặt qua `PATCH /me` / `POST /me/avatar` không bao giờ bị đè. Không cần inbox claim — công việc là một UPDATE có điều kiện về giá trị cố định, redelivery là no-op |
| `auth.username-events` | `UsernameChangedEvent` | `eventId, occurredAt, userId, username, previousUsername` | `UsernameChangedEventConsumer` → `applyUsernameChange`: đổi bản sao `username` của profile (không đụng `displayName`). Claim `eventId` vào `inbox_events` trong cùng transaction. Profile chưa tồn tại (rename đến trước `UserRegisteredEvent` vì hai topic khác nhau) → **throw** để `kafka-lib` retry, thay vì bỏ qua — bỏ qua thì event đăng ký đến sau sẽ ghi username cũ và không gì sửa lại được. Retry chỉ thêm 3 lần, cách nhau 1 giây (đủ cho độ trễ thường gặp), sau đó event nằm ở `auth.username-events.DLT` và profile giữ handle cũ cho tới khi event được replay. Cột `username` không unique ở đây (auth-service giữ uniqueness): khi gán handle cho một profile, profile khác còn giữ đúng handle đó (không phân biệt hoa thường) bị gỡ handle (`username = null`) cho tới khi rename của chính nó tới — auth-service chỉ cho một tài khoản giữ một handle tại một thời điểm, nên bản sao kia chắc chắn đã cũ. Nhờ vậy `GET /by-username/{username}` trả về người đang giữ handle chứ không phải bản sao cũ |

`auth.user-events` chỉ mang đúng một loại event nên hai consumer parse thẳng payload vào `UserRegisteredEvent`, không đọc header. Đó là lý do `auth-service` phát `SocialAvatarDiscoveredEvent` và `UsernameChangedEvent` trên topic **riêng** (`auth.social-avatar-events`, `auth.username-events`) chứ không dùng chung topic này.

`kafka-lib` error handler: `user-service` **có** dùng → consumer lỗi retry 3 lần → `<topic>.DLT`.
