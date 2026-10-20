# Story Service — API Contract

Tài liệu này mô tả những gì client cần biết để dùng `story-service`: đăng story ảnh/video, xem
story của người mình follow, xem ai đã xem story của mình. Thiết kế: `docs/superpowers/specs/2026-10-20-stories-design.md`.

Base URL (qua gateway): `/api/v1/stories`. Mọi response bọc trong `ApiResponse<T>`. Không gọi thẳng `story-service:8086`.

Quy tắc token: `GET /users/{userId}` và `GET /{storyId}` không cần token (người chưa đăng nhập chỉ
thấy story PUBLIC của tài khoản công khai). Mọi endpoint khác cần access token; thiếu token thì
service trả 403, gateway trả 401.

## 1. Vòng đời một story

```
POST /upload-url → trình duyệt POST file thẳng lên MinIO (presigned, stories/{userId}/{id}.{ext})
POST /           → caption được chấm (moderation-lib); story lưu ở PENDING_MODERATION
                 → story.story-events → media-worker chấm ảnh/video
                 → media.story-moderation-events → ACTIVE | HELD | REJECTED
```

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

## 5. Mã lỗi

| Code | HTTP | Khi nào |
|---|---|---|
| `UNSUPPORTED_UPLOAD_TYPE` | 400 | loại file không nằm trong §3 |
| `UPLOAD_NOT_FOUND` | 400 | chưa upload file lên `uploadUrl`, hoặc upload đã hết hạn |
| `FOREIGN_UPLOAD` | 403 | `mediaUrl` không phải upload của mình, hoặc đã dùng cho story khác |
| `STORY_CAPTION_REJECTED` | 422 | caption chứa từ bị cấm — không lưu gì, sửa caption rồi đăng lại cùng file |
| `TOO_MANY_FOLLOWED_USERS` | 400 | `followedUserIds` quá 500 |
| `STORY_NOT_FOUND` | 404 | không tồn tại, đã xoá, đã hết hạn, hoặc người gọi không được xem |
| `NOT_STORY_OWNER` | 403 | sửa/xoá/xem người xem story của người khác |
| `UPLOAD_URL_UNAVAILABLE` | 503 | MinIO không ký được URL |
