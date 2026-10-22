# Story Service — API Contract

Tài liệu này mô tả những gì client cần biết để dùng `story-service`: đăng story ảnh/video, xem
story của người mình follow, xem ai đã xem story của mình. Thiết kế: `docs/superpowers/specs/2026-10-20-stories-design.md`.

Base URL (qua gateway): `/api/v1/stories`. Mọi response bọc trong `ApiResponse<T>`. Không gọi thẳng `story-service:8086`.

Quy tắc token: `GET /users/{userId}`, `GET /{storyId}` và `GET /rings` không cần token (người chưa
đăng nhập chỉ thấy story PUBLIC của tài khoản công khai). Mọi endpoint khác cần access token;
thiếu token thì service trả 403, gateway trả 401. Ngoại lệ duy nhất là endpoint nội bộ (§6): dành cho
service khác, gateway chặn hẳn.

## 1. Vòng đời một story

```
POST /upload-url → trình duyệt POST file thẳng lên MinIO (presigned, stories/{userId}/{id}.{ext})
POST /           → caption được chấm (moderation-lib); story lưu ở PENDING_MODERATION
                 → story.story-events → media-worker chấm ảnh/video
                 → media.story-moderation-events → ACTIVE | HELD | REJECTED
                 → ACTIVE + PUBLIC/FRIENDS → story.activation-events (StoryActivatedEvent, key userId) → notification-service
```

Một story chỉ được thông báo một lần — khi nó thành `ACTIVE`, hoặc, nếu đăng ở chế độ `PRIVATE`, khi
chủ story mở rộng sang `PUBLIC`/`FRIENDS` trong 24h hiệu lực; story đã sống từ trước khi tính năng này
ra mắt thì không bao giờ được thông báo.

| Status | Ai thấy | Hết hạn |
|---|---|---|
| `PENDING_MODERATION` | chỉ chủ story | 48h sau khi đăng nếu verdict không bao giờ tới |
| `ACTIVE` | khán giả theo `visibility` | 24h tính từ lúc được duyệt |
| `HELD` (model không chắc, hoặc không chấm được) | chỉ chủ story | 48h sau khi đăng; không có hàng chờ admin |
| `REJECTED` | chỉ chủ story | 48h sau khi đăng |

`status` và `viewCount` chỉ có trong response cho chủ story; người khác nhận `null`.

## 2. Quyền xem

Giống hệt video (`VideoVisibility`):

| `visibility` | Ai thấy (ngoài chủ story) |
|---|---|
| `PUBLIC` | mọi người — chỉ người follow nếu tài khoản chủ story là riêng tư |
| `FRIENDS` | người follow hai chiều |
| `PRIVATE` | không ai |

Thêm vào đó: chủ story bị ban hoặc đã đóng tài khoản thì story bị ẩn; hai người block nhau thì
không thấy story của nhau. Story không xem được trả **404** (không phải 403).

## 3. Media

`mediaUrl` là presigned GET **sống 1 giờ**, chỉ cấp cho story người gọi đang được xem. Hết hạn thì
gọi lại endpoint để lấy URL mới. Prefix `stories/` không đọc công khai được.

Loại file và giới hạn: `image/jpeg`, `image/png`, `image/webp` ≤ 10 MB; `video/mp4`, `video/webm`
≤ 50 MB và ≤ 60 giây (dài hơn → `REJECTED`). Giới hạn dung lượng được ký vào policy upload, MinIO tự
từ chối file lớn hơn.

## 4. Endpoints

| Method | Path | Token | Body / params | Trả về |
|---|---|---|---|---|
| POST | `/upload-url` | cần | `{contentType}` | `{uploadUrl, formFields, fileUrl, expiresInSeconds}` — giống `POST /videos/upload-url` |
| POST | `/` | cần | `{mediaUrl: fileUrl, caption?: ≤300, visibility}` | `201` + `StoryResponse` (của chủ) |
| GET | `/feed` | cần | `followedUserIds` (≤ 500, như Following feed của video) | `StoryGroupResponse[]` |
| GET | `/rings` | không | `userIds` (≤ 100) | `StoryRingResponse[]` — chỉ những id có vòng, theo thứ tự gửi lên |
| GET | `/users/{userId}` | không | — | `StoryResponse[]`, cũ trước |
| GET | `/{storyId}` | không | — | `StoryResponse` |
| PATCH | `/{storyId}/visibility` | chủ story | `{visibility}` | `StoryResponse` |
| POST | `/{storyId}/views` | cần | — | `204`; xem lại hoặc tự xem story của mình thì không đếm |
| GET | `/{storyId}/viewers` | chủ story | `before?` (ISO instant), `size?` (1–50, mặc định 20) | `[{viewerId, viewedAt}]`, mới trước |
| DELETE | `/{storyId}` | chủ story | — | xoá mềm |

`StoryResponse`: `id, userId, mediaUrl, mediaType (IMAGE|VIDEO), caption, visibility, status, durationSeconds, viewCount, viewedByMe, createdAt, expiresAt`.

`StoryGroupResponse`: `{userId, stories, hasUnseen}`. Nhóm của chính người gọi đứng đầu (mọi status,
`hasUnseen=false`), sau đó các nhóm còn story chưa xem, rồi theo story mới nhất. Trong một nhóm, story
xếp cũ trước — đúng thứ tự phát. Profile (avatar, tên) hydrate qua `GET /api/v1/users?ids=`.

`StoryRingResponse`: `{userId, hasUnseen}` — vòng quanh một avatar, không kèm media (không ký URL).
Id của người khác có mặt khi họ còn ít nhất một story `ACTIVE` mà người gọi được xem (§2);
`hasUnseen` khi trong số đó còn story người gọi chưa mở, và luôn `true` với người chưa đăng nhập.
Id của chính người gọi có mặt khi họ còn bất kỳ story nào chưa hết hạn, nhưng `hasUnseen` chỉ khi
một trong số đó là `ACTIVE` — story đang chờ duyệt hay bị giữ thì vòng xám. Id không có vòng thì
vắng mặt trong kết quả; id trùng chỉ tính một lần.

## 5. Mã lỗi

| Code | HTTP | Khi nào |
|---|---|---|
| `UNSUPPORTED_UPLOAD_TYPE` | 400 | loại file không nằm trong §3 |
| `UPLOAD_NOT_FOUND` | 400 | chưa upload file lên `uploadUrl`, hoặc upload đã hết hạn |
| `FOREIGN_UPLOAD` | 403 | `mediaUrl` không phải upload của mình, hoặc đã dùng cho story khác |
| `STORY_CAPTION_REJECTED` | 422 | caption chứa từ bị cấm — không lưu gì, sửa caption rồi đăng lại cùng file |
| `TOO_MANY_FOLLOWED_USERS` | 400 | `followedUserIds` quá 500 |
| `TOO_MANY_RING_USERS` | 400 | `userIds` quá 100 |
| `STORY_NOT_FOUND` | 404 | không tồn tại, đã xoá, đã hết hạn, hoặc người gọi không được xem |
| `NOT_STORY_OWNER` | 403 | sửa/xoá/xem người xem story của người khác |
| `UPLOAD_URL_UNAVAILABLE` | 503 | MinIO không ký được URL |

## 6. Endpoint nội bộ

Dành cho service khác, không phải client.

| Method | Path | Trả về |
|---|---|---|
| GET | `/api/v1/stories/internal/{storyId}/announcement` | `{userId, visibility}` — chủ story và `visibility` (`PUBLIC` \| `FRIENDS` \| `PRIVATE`) **hiện tại** |

Người gọi là notification-service, ngay trước khi báo cho người theo dõi một story mới: event
`story.activation-events` chỉ mang `storyId` và `userId`, còn từ lúc event được phát story có thể đã bị xoá,
hết hạn, đổi `visibility`, hoặc chủ story bị ban / đóng tài khoản.

`404 STORY_NOT_FOUND` khi story không còn gì để thông báo: không tồn tại hoặc đã xoá, đã hết hạn, chưa
`ACTIVE` (`PENDING_MODERATION`, `HELD`, `REJECTED`), hoặc chủ story đang bị ban / đã đóng tài khoản. Story
`PRIVATE` vẫn trả `200` với `visibility: "PRIVATE"`; có báo cho ai hay không là việc của người gọi.

Chỉ nhận header `X-Internal-Token` (= `INTERNAL_API_TOKEN`, cùng giá trị ở mọi service). Không có token, hoặc
chỉ có access token của user, đều bị `403` với mọi method. Gateway `denyAll` cả prefix
`/api/v1/stories/internal/**` (`403` khi có token, `401` khi không), nên từ ngoài không với tới được; token
là lớp còn lại cho request đi thẳng vào `story-service:8086`.
