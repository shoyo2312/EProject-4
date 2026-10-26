# Video Service — API Contract cho Flutter Mobile

Tài liệu này mô tả **những gì client (Flutter/Dart) cần biết** để tích hợp đúng với `video-service`. Không phải tài liệu thiết kế backend — chỉ phần hợp đồng API (contract). Đọc kèm `docs/auth-service-api.md` để lấy `accessToken`.

Khác `user-service` (bắt buộc token ở mọi endpoint): ở đây **mọi `GET` đều public**, chỉ `POST`/`DELETE` mới bắt buộc token. Nhưng **có token hay không vẫn ảnh hưởng đến kết quả của `GET`** — xem mục 3.4 và 3.5.

## 1. Tech stack liên quan đến client

| Thành phần | Công nghệ |
|---|---|
| Giao thức | REST/HTTP, JSON |
| Auth | JWT access token, Bearer scheme — bắt buộc với `POST`/`DELETE`, optional với `GET` |
| Entry point cho mobile | **api-gateway** (`:8080`), KHÔNG gọi thẳng `video-service:8083` |
| Base URL (qua gateway) | `http://<gateway-host>:8080/api/v1/videos` |
| Docs tương tác | `http://localhost:8083/swagger-ui.html` (chạy trực tiếp video-service khi dev) |

## 2. Response envelope & phân trang

Giống hệt `auth-service`/`user-service` — mọi response bọc trong `ApiResponse<T>` (`success`/`data`/`code`/`message`/`timestamp`). Nhưng **hai endpoint danh sách phân trang theo hai kiểu khác nhau**, nên phía Dart cần hai wrapper riêng:

- `/users/{userId}` — phân trang theo `page`, đúng format của user-service (`PageResponse<T>`, mô tả ngay dưới);
- `/feed` và `/feed/following` — phân trang theo **cursor**, không có `page`/`totalElements`/`totalPages`. Xem mục 3.3 và 3.3b.

`PageResponse<T>` — chỉ áp dụng cho `/users/{userId}`:

```json
{
  "success": true,
  "data": {
    "content": [ { "id": "7312458901234567", "userId": 123456789012345, "status": "PUBLISHED" } ],
    "page": { "size": 20, "number": 0, "totalElements": 42, "totalPages": 3 }
  },
  "timestamp": "2026-08-12T10:00:00Z"
}
```

`size` bị chặn trên ở **50** (xin lớn hơn thì bị kẹp xuống, không báo lỗi); không truyền thì mặc định **20**. Hai giới hạn này giống nhau ở cả hai endpoint. `last` = `number + 1 >= totalPages` (chỉ có ở `PageResponse`).

**Sắp xếp là cố định, không cấu hình được:** cả `/feed` lẫn `/users/{userId}` luôn trả theo `createdAt` giảm dần (mới nhất trước) — thứ tự này nằm sẵn trong query ở server. Đừng thiết kế UI dựa trên việc truyền `sort`.

**Cẩn thận trùng/nhảy item khi infinite scroll — chỉ với `/users/{userId}`.** Đó là phân trang theo offset: nếu có video mới chèn vào **đầu** danh sách giữa lúc load trang 0 và trang 1, các item cũ bị đẩy lùi xuống và trả lại lần nữa ở trang sau. Phía Flutter phải **khử trùng lặp theo `id`** khi append (giữ một `Set<String>` các id đã có). `/feed` không có vấn đề này vì cursor neo vào video cuối của trang trước.

## 3. Endpoints

Base path: `/api/v1/videos`.

### 3.1 `POST /upload-url` — xin URL upload file
**Bắt buộc token.** Trả `200 OK`. Đây là bước 1 của việc đăng video: server ký sẵn một **POST policy**, client gửi file lên object storage bằng một request `multipart/form-data`, **bytes không đi qua video-service**. Đăng video (mục 3.2) là bước 2.

Request (`UploadUrlRequest`):
```json
{
  "contentType": "video/mp4"    // bắt buộc; chỉ nhận video/mp4, video/quicktime, video/webm
                                // tham số đi kèm được bỏ qua: "video/mp4; charset=utf-8" vẫn hợp lệ
}
```

Response `data` → `UploadUrlResponse`:
```json
{
  "uploadUrl": "http://localhost:9000/video-media",
  "formFields": {
    "key": "raw/123/7312458901234567.mp4",
    "Content-Type": "video/mp4",
    "policy": "eyJleHBpcmF0aW9uIjoi...",
    "x-amz-algorithm": "AWS4-HMAC-SHA256",
    "x-amz-credential": "minioadmin/20260812/us-east-1/s3/aws4_request",
    "x-amz-date": "20260812T100000Z",
    "x-amz-signature": "3f2a9b..."
  },
  "fileUrl": "s3://video-media/raw/123/7312458901234567.mp4",
  "expiresInSeconds": 900
}
```

- `uploadUrl` là URL của bucket để POST form multipart lên — **không còn chứa chữ ký** (chữ ký nằm trong `formFields`).
- `formFields` là **hộp đen**: client không đọc, không sửa, không tự thêm field. Số lượng và tên field có thể đổi theo cấu hình storage; policy đã ký sẵn cả `key`, `Content-Type` và khoảng `content-length-range` `[1, 500 MB]`.
- `fileUrl` không đổi: đây là giá trị đưa vào `rawFileUrl` ở mục 3.2.

Luồng đầy đủ phía client:
1. `POST /upload-url` → nhận `uploadUrl` + `formFields` + `fileUrl`.
2. Dựng một request `multipart/form-data`: **thêm mọi entry trong `formFields` làm form field trước**, rồi thêm phần **`file`** (bytes của file) ở **CUỐI cùng** — S3/MinIO POST bắt buộc `file` là field cuối. `POST` form đó lên `uploadUrl`. **Không** gửi kèm `Authorization`, và **không** tự đặt header `Content-Type` cho request (HTTP client tự sinh boundary; content-type của object lấy từ field `Content-Type` trong form).
   - Thành công: storage trả `204 No Content` (một số cấu hình trả `201`).
   - File vượt **500 MB**: storage từ chối ngay với `400 Bad Request`, mã lỗi S3 `EntityTooLarge`, **không byte nào được lưu**. Giới hạn ký thẳng trong policy nên client không bypass được.
3. `POST /` (mục 3.2) với `rawFileUrl` = **`fileUrl`**, không phải `uploadUrl`.

> **Dùng `fileUrl`, không dùng `uploadUrl`, cho `rawFileUrl`.** `uploadUrl` chỉ là địa chỉ bucket để upload và sẽ trượt allow-list ở mục 3.2. `fileUrl` là vị trí thật của file, media-worker đọc nó có thể hàng giờ sau.

> **Policy hết hạn thì xin lại, đừng cache.** Quá `expiresInSeconds` (mặc định 15 phút) mà chưa POST xong → storage trả 403, gọi lại `/upload-url` để lấy policy mới (key mới, không ghi đè cái cũ).

Không có bước "báo upload xong": nếu client bỏ ngang giữa chừng thì chỉ còn một object mồ côi trong bucket, không có bản ghi Video nào được tạo. Object mồ côi tự hết hạn sau 7 ngày — bucket có lifecycle rule trên prefix `raw/` (khai ở `scripts/minio/lifecycle.json`, do `minio-init` áp). Nghĩa là **POST file xong thì gọi `POST /` (mục 3.2) trong vòng 7 ngày**, quá hạn file biến mất dù `fileUrl` vẫn còn trong tay client.

Lỗi: `UNSUPPORTED_UPLOAD_TYPE` (400) khi `contentType` ngoài 3 loại trên, `VALIDATION_ERROR` (400) khi thiếu `contentType`, `401` (thiếu/hết hạn token), `UPLOAD_URL_UNAVAILABLE` (503) khi server không ký được URL — lỗi phía server, không phải request sai.

### 3.2 `POST /` — đăng video
**Bắt buộc token.** Trả `201 Created`.

Request (`CreateVideoRequest`):
```json
{
  "title": "Tiêu đề",                                // bắt buộc, một dòng, tối đa 150 ký tự, không được rỗng sau khi làm sạch
  "description": "Mô tả",                             // optional, tối đa 2000 ký tự
  "rawFileUrl": "s3://video-media/raw/123456789012345/7312.mp4",  // bắt buộc, tối đa 500 ký tự, xem ràng buộc bên dưới
  "visibility": "PUBLIC",                             // bắt buộc: PUBLIC | PRIVATE
  "tags": ["dance", "food"]                           // optional, tối đa 10 tag, mỗi tag tối đa 50 ký tự
}
```

**Làm sạch trước khi kiểm tra độ dài.** Server chuẩn hoá NFC (tiếng Việt gõ kiểu tổ hợp được ghép lại), bỏ ký tự vô hình (zero-width, đảo chiều U+202E, ký tự "lấp chỗ" Hangul/Braille), gộp khoảng trắng, tối đa một dòng trống liên tiếp trong `description`, và đổi xuống dòng trong `title`/tag thành dấu cách. Mọi giới hạn ký tự tính theo **code point** sau khi làm sạch (emoji tính 1) — đếm phía client bằng `text.runes.length` (Dart) / `[...text].length` (JS). Tiêu đề chỉ toàn ký tự vô hình → `VALIDATION_ERROR` (400).

**Kiểm duyệt chữ.** `title`, `description` và danh sách tag cuối cùng (kể cả hashtag lấy từ mô tả) được đọc bằng bộ luật chữ (blocklist tiếng Việt + tín hiệu spam) **trước khi lưu**:
- Từ ngữ nặng nhất trong blocklist → `422` với mã theo trường: `VIDEO_TITLE_REJECTED`, `VIDEO_DESCRIPTION_REJECTED` hoặc `VIDEO_TAGS_REJECTED` (kiểm theo thứ tự đó, báo trường đầu tiên vi phạm). **Không lưu gì** — file đã upload vẫn còn, sửa chữ rồi gửi lại **đúng `rawFileUrl` cũ**, không cần upload lại. Server không nói từ nào; hiện thông báo dưới đúng ô nhập.
- Thô tục/xúc phạm mức vừa, link (trừ link tới YouTube, Facebook, Instagram, TikTok, X, Threads), số điện thoại, email, cờ bạc → video **vẫn được tạo** (`201`) và chạy pipeline như thường, nhưng kiểm duyệt xong sẽ thành `PENDING_REVIEW` thay vì `PUBLISHED` — chờ admin, kể cả khi hình ảnh không có vấn đề gì.
- Sau bộ luật, một model phân loại tiếng Việt (PhoBERT, chỉ bắt lời lẽ thù ghét) đọc cả ba trường. Model **chỉ có thể** đưa video vào `PENDING_REVIEW`, không bao giờ trả `422`. Model không chạy được thì bộ luật quyết một mình.
- Chữ không bao giờ tự làm video thành `REJECTED`.

`rawFileUrl` **không phải URL client tự nghĩ ra**. Nó là `fileUrl` trả về từ `POST /upload-url` (mục 3.1), và server chặn theo allow-list:
- scheme phải là `https` hoặc `s3` — mọi scheme khác (`http`, `file:`, `data:`, `javascript:`) bị từ chối;
- với `https`: host phải nằm trong danh sách host được cấu hình (CDN nội bộ);
- với `s3://`: phần authority là **tên bucket**, phải nằm trong danh sách bucket được cấu hình.

Sai bất kỳ điều nào → `VALIDATION_ERROR` (400). Lý do chặn: URL này được backend (media-worker) tự đi tải về, nên một URL tuỳ ý sẽ biến server thành công cụ gọi ra ngoài hộ kẻ tấn công.

`tags` được server **chuẩn hoá** trước khi lưu: lowercase, cắt khoảng trắng, bỏ dấu `#` đầu, bỏ tag rỗng, bỏ trùng, giữ nguyên thứ tự client gửi. Nên `["#Dance", " dance ", "Food"]` lưu thành `["dance", "food"]` — client đừng tự so sánh tag với chuỗi chưa chuẩn hoá. Quá 10 tag hoặc một tag quá 50 ký tự → `VALIDATION_ERROR` (400).

Ngoài allow-list còn một ràng buộc nữa: object key phải nằm dưới `raw/{userId}/` của **chính tài khoản đang gọi** — đúng key mà mục 3.1 vừa cấp. Trỏ vào `raw/` của người khác (hoặc vào key không do mục 3.1 cấp, ví dụ file đã transcode) → `FOREIGN_UPLOAD` (400). Đường dẫn được chuẩn hoá trước khi kiểm tra, nên `raw/{mình}/../{người khác}/` cũng bị chặn. Cứ dùng nguyên `fileUrl` nhận được, đừng ghép tay.

**Một upload là một video.** Publish lại cùng `rawFileUrl` → `RAW_FILE_ALREADY_PUBLISHED` (400), kể cả khi video cũ đã bị xoá. Đây là chỗ hay gặp khi client **retry sau timeout**: lần đầu có thể đã thành công dù response không về tới nơi. Gặp mã này thì coi như đã publish xong, đừng xin `fileUrl` mới rồi upload lại — cứ đọc lại danh sách video của mình.

Response `data` → `VideoResponse`:
```json
{
  "id": "7312458901234567",      // Snowflake id dạng CHUỖI, không phải số — xem lưu ý bên dưới
  "userId": 123456789012345,
  "title": "Tiêu đề",
  "description": "Mô tả",         // có thể null
  "thumbnailUrl": null,           // null cho tới khi transcode xong
  "previewUrl": null,             // WebP động cho hover; null nghĩa là fallback về thumbnailUrl
  "hlsUrl": null,                 // null cho tới khi transcode xong
  "durationSeconds": null,        // null cho tới khi transcode xong
  "status": "PROCESSING",
  "visibility": "PUBLIC",
  "viewCount": 0,
  "likeCount": 0,
  "commentCount": 0,              // null khi commentsDisabled HOẶC accountCommentsOff
  "commentsDisabled": false,      // công tắc tắt comment của RIÊNG video này (chủ video bật/tắt)
  "accountCommentsOff": false,    // tài khoản chủ video đặt "Ai được bình luận" = No one → mọi video của họ coi như tắt comment, kể cả với chính chủ
  "ownerPrivate": false,          // tài khoản chủ video đang riêng tư → video PUBLIC chỉ follower xem được; hiện ổ khoá. `visibility` vẫn là lựa chọn của chủ video
  "tags": ["dance", "food"],      // đã chuẩn hoá, có thể rỗng nhưng không bao giờ null
  "createdAt": "2026-08-12T10:00:00Z",
  "failureReason": null           // chuỗi giải thích khi status = FAILED; null ở mọi trạng thái khác
}
```

> **Comment tắt khi `commentsDisabled || accountCommentsOff`.** Khi một trong hai là `true`: ẩn ô nhập, ẩn số comment (server đã trả `commentCount: null`), hiện "This creator has turned off commenting". Chỉ công tắc "Allow comments" của chủ video mới đọc riêng `commentsDisabled` — `accountCommentsOff` là cài đặt tài khoản, không phải trạng thái công tắc đó.

> **`id` là `String`, không phải `int`.** Khai báo `String id` trong model Dart. Đây là Snowflake id 19 chữ số — parse thành số vẫn vừa `int` 64-bit của Dart native, nhưng **tràn trên Flutter Web** (JS number chỉ chính xác tới 2^53) và sẽ sai âm thầm. Riêng `userId` thì server trả về dạng số.

> **`201` KHÔNG có nghĩa là video xem được.** Video vào trạng thái `PROCESSING`, chưa có `hlsUrl` để phát, và **chưa xuất hiện trên `/feed`**. Việc transcode chạy bất đồng bộ qua Kafka, mất từ vài giây tới vài phút. Transcode xong video sang `PENDING_MODERATION` (kiểm duyệt tự động), rồi mới thành `PUBLISHED`, `PENDING_REVIEW` (chờ admin) hoặc `REJECTED`. Client phải poll `GET /{videoId}` cho tới khi `status` rời khỏi `PROCESSING`/`PENDING_MODERATION` — `PUBLISHED` là phát được, `FAILED` là hỏng (báo user đăng lại), `PENDING_REVIEW` là "đang chờ duyệt", `REJECTED` là bị từ chối. Poll giãn dần (vd. 2s → 5s → 10s, dừng sau ~5 phút và hiện "đang xử lý, quay lại sau") thay vì poll dày — gateway giới hạn 20 request/giây theo IP.

> **`FAILED` có `failureReason` — hiện thẳng cho user.** Khi `status = FAILED`, `failureReason` là chuỗi giải thích do media-worker sinh ra, ví dụ `"Video is 12m30s; the maximum is 10m00s."` (clip dài quá 10 phút) hoặc `"Video is 812 MB; the maximum is 500 MB."` (file lọt qua client nhưng media-worker vẫn từ chối vì vượt kích thước). Với mọi `status` khác, `failureReason` là `null`. Nếu `failureReason` là `null` mà vẫn `FAILED` (lỗi transcode không phân loại được), hiện thông điệp mặc định kiểu "Xử lý video thất bại, thử đăng lại".

Lỗi: `VALIDATION_ERROR` (400), `FOREIGN_UPLOAD` (400), `UPLOAD_NOT_FOUND` (400), `RAW_FILE_ALREADY_PUBLISHED` (400), `VIDEO_TITLE_REJECTED` / `VIDEO_DESCRIPTION_REJECTED` / `VIDEO_TAGS_REJECTED` (422), `401` (thiếu/hết hạn token), `UPLOAD_URL_UNAVAILABLE` (503) khi server không kiểm được file trong storage.

### 3.3 `GET /feed`
Không cần token. **Phân trang bằng cursor, không phải `page`** — khác mục 3.5. Query param: `?cursor=<nextCursor>&size=20`. Trả `200 OK`, `data` → `CursorPage<VideoResponse>`:

```json
{
  "items": [ /* VideoResponse[] */ ],
  "nextCursor": "1755400000000_7312458901234567"
}
```

Cách dùng:
- Trang đầu: **không gửi** `cursor`.
- Trang tiếp: gửi lại **nguyên văn** `nextCursor` của response trước.
- `nextCursor = null` là hết feed. Đó là tín hiệu dừng duy nhất — **không có `totalElements`, `totalPages`, hay `page`**.

`nextCursor` là **chuỗi mờ (opaque)**: client truyền lại y nguyên, không parse, không tự dựng, không hiển thị. Định dạng bên trong là chuyện của server và có thể đổi bất cứ lúc nào.

`size` mặc định 20, tối đa 50. Gửi lớn hơn thì bị **kẹp xuống 50**, không báo lỗi.

Vì sao bỏ `?page=`: feed là danh sách vô hạn, mà `page=500` bắt Mongo bước qua 10.000 document rồi vứt đi, và mỗi request còn tốn thêm một lần đếm toàn bộ collection để trả con số không màn hình nào hiển thị. Cursor bắt đầu đúng chỗ trang trước dừng, nên trang 500 rẻ ngang trang 1.

> **Feed đổi trong lúc user đang cuộn là bình thường.** Cursor neo vào video cuối của trang trước, nên video mới đăng sau đó **không** chen vào giữa và **không** gây lặp/nhảy item như `page` cũ. Muốn thấy video mới thì kéo-để-làm-mới, tức gọi lại mà không kèm `cursor`.

Chỉ trả video **`PUBLISHED` + `PUBLIC`**, chưa bị xoá, của tài khoản **không riêng tư**. Video của chính mình đang `PROCESSING`/`PRIVATE` **không** xuất hiện ở đây kể cả khi có gửi token — muốn xem video của mình thì dùng mục 3.5. Video của tài khoản riêng tư không bao giờ lên feed này, kể cả với follower — follower thấy chúng ở tab Following (mục 3.3b).

Lỗi: `INVALID_FEED_CURSOR` (400 — cursor không phải do server này cấp, thường do client tự sửa hoặc lưu nhầm). Xử lý: bỏ cursor đang giữ và tải lại từ đầu.

### 3.3b `GET /feed/following`
Tab **Following**: đúng feed ở mục 3.3, nhưng chỉ lấy video của những tài khoản người xem đang follow. Cùng dạng `CursorPage<VideoResponse>`, cùng giao thức cursor, cùng `size` mặc định 20 / tối đa 50.

Query param: `?followedUserIds=1,2,3&cursor=<nextCursor>&size=20`.

**Danh sách tác giả do client truyền vào, service này không tự đọc đồ thị follow.** Follow graph nằm ở user-service; client gọi `GET /api/v1/users/{userId}/following` (phân trang) trước, gom `userId` lại rồi truyền xuống đây — cùng cách `story-service` nhận feed của nó. Đổi lại video-service không phải gọi đồng bộ sang service khác trên đường đọc nóng nhất của nó.

- `followedUserIds` rỗng hoặc không gửi → trả **trang rỗng**, *không* phải feed công khai. Follow chưa ai thì tab Following trống.
- Tối đa **500** id một request; quá thì `TOO_MANY_FOLLOWED_USERS` (400) chứ không cắt bớt im lặng — một feed thiếu hẳn vài creator là feed sai mà không ai nhìn ra được.
- Id trùng nhau được khử ở server, client không cần lọc trước.

Bộ lọc hiển thị giống mục 3.3: chỉ `PUBLISHED` + `PUBLIC` và chưa xoá. Follow một người không cho bạn thấy video `PRIVATE`, video đang `PROCESSING`, hay video bị `TAKEN_DOWN` của họ. Khác mục 3.3 ở một chỗ: video của tài khoản **riêng tư** có xuất hiện ở đây — nhưng chỉ với follower của họ. Vì danh sách id do client tự khai, server kiểm tra lại: tài khoản riêng tư trong `followedUserIds` mà người gọi không follow bị **bỏ khỏi danh sách trước khi truy vấn** (trang không bị hụt, cursor không lệch). **Gửi token nếu đã đăng nhập** — không có token thì mọi tài khoản riêng tư đều bị bỏ.

Lỗi: `INVALID_FEED_CURSOR` (400) như mục 3.3, và `TOO_MANY_FOLLOWED_USERS` (400).

### 3.4 `GET /{videoId}`
Không bắt buộc token, **nhưng nên gửi nếu đã đăng nhập**. Trả `200 OK`, `data` → `VideoResponse`.

Quy tắc hiển thị:
- **Chủ video** (token khớp `userId` của video): xem được ở mọi trạng thái — `PROCESSING`, `PRIVATE`, `FAILED`, `TAKEN_DOWN`.
- **Người khác / không có token**: chỉ xem được video `PUBLISHED` + `PUBLIC`. Nếu chủ video để tài khoản **riêng tư** (`ownerPrivate = true`) thì video `PUBLIC` chỉ follower của họ xem được; người khác nhận `VIDEO_NOT_FOUND` như với video `PRIVATE`. Video `FRIENDS` vẫn theo luật bạn bè (follow lẫn nhau).

Lỗi: `VIDEO_NOT_FOUND` (404) — dùng chung cho **cả 4 trường hợp**: id không tồn tại, video đã bị xoá, video `PRIVATE` của người khác, hoặc video chưa/không còn `PUBLISHED` (đang `PROCESSING`, `FAILED`, hoặc bị `TAKEN_DOWN`). Ngoài ra, nếu người gọi và chủ video **đang chặn nhau** (bất kể chiều nào) thì cũng là 404 — interaction-service hỏi đúng endpoint này trước mỗi like/comment/save/repost/share/view, nên đây là chỗ chặn tài khoản bị block tương tác với video của người đã block. Server **cố tình** không phân biệt để không lộ sự tồn tại của video riêng tư. Hệ quả phía client: **404 ở đây không chứng minh video không tồn tại** — nếu đang poll sau khi upload mà nhận 404, khả năng cao là quên gửi token chứ không phải video biến mất.

### 3.4b `GET /batch?ids=…`
Nạp **một danh sách id đã có sẵn** trong đúng một request. Sinh ra cho trường hợp client cầm một bảng xếp hạng mà không cầm video: `GET /api/v1/recommendations/feed` chỉ trả `videoId` + `score`, vì recommendation-service không có đường đọc vào Mongo của video-service và không được phép có.

Không bắt buộc token, nhưng gửi nếu đã đăng nhập — quy tắc hiển thị y hệt mục 3.4.

```
GET /api/v1/videos/batch?ids=7312458901234567,7312458901234568
```

Trả `200 OK`, `data` → `VideoResponse[]`.

Ba điều phải biết, cả ba đều là chỗ dễ sai:

1. **Thứ tự trả về = thứ tự hỏi.** Danh sách id chính là thứ hạng; trả theo thứ tự Mongo sẽ vứt bỏ thứ hạng đó **mà không có triệu chứng gì** — feed vẫn hiện, chỉ là hết xếp hạng.
2. **Kết quả có thể ngắn hơn đầu vào.** Id không giải ra thứ người gọi được xem — đã xoá, đang `PROCESSING`, `PRIVATE` của người khác, hoặc không tồn tại — **bị bỏ im lặng**, không phải lỗi. Feed gọi tên một video vừa bị xoá vài giây trước là chuyện bình thường: `VideoDeletedEvent` mất khoảng 5 giây để lan.
3. **Tối đa 50 id** (cùng trần với `size` ở mục 3.3). Id trùng được lọc trước khi đếm, nên nhồi một id lặp lại không ăn hết hạn mức.

Vì sao không gọi `GET /{videoId}` 20 lần: gateway giới hạn **20 req/s theo IP**, mà mọi người xem đứng sau cùng một proxy thì dùng chung một IP. Một lần cuộn feed là hết sạch hạn mức.

### 3.5 `GET /users/{userId}`
Danh sách video của 1 user. Không bắt buộc token. **Phân trang bằng `?page=0&size=20` (offset), KHÁC feed ở mục 3.3** — đây là lưới hữu hạn, người dùng nhảy trang được và chủ tài khoản có nhu cầu biết tổng số, nên `Page` vẫn đúng chỗ ở đây. Trả `200 OK`, `data` → `Page<VideoResponse>`.

- Gọi **chính mình** (token khớp `userId`): trả **tất cả** video chưa xoá, gồm cả `PROCESSING`, `PRIVATE`, `FAILED`, `TAKEN_DOWN`. Đây là endpoint cho màn "Video của tôi".
- Gọi user khác (hoặc không token): chỉ `PUBLISHED` + `PUBLIC`.
- Tài khoản **riêng tư**, người gọi không phải follower (hoặc không token): `PRIVATE_ACCOUNT` (403) — UI hiện "This account is private" thay vì lưới trống. Follower xem lưới như bình thường. `GET /users/{userId}/stats` vẫn công khai (các con số trên header profile).

Không có lỗi `USER_NOT_FOUND` — `userId` không tồn tại chỉ trả về trang rỗng (`content: []`), vì video-service không giữ dữ liệu user.

### 3.5b `GET /me` — bảng Bài đăng của TikTok Studio
**Bắt buộc token** (thiếu token → 403 như mọi endpoint ghi). Luôn trả video của **chính người gọi**, mọi status trừ video đã xoá, giống mục 3.5 khi gọi chính mình. Phân trang offset `?page=0&size=20` (tối đa 50), `data` → `Page<VideoResponse>`.

| Tham số | Giá trị | Ý nghĩa |
|---|---|---|
| `status` | một `VideoStatus` (mục 4) | chỉ video ở trạng thái đó |
| `visibility` | `PUBLIC` / `FRIENDS` / `PRIVATE` | chỉ video có quyền xem đó |
| `q` | chuỗi | có trong tiêu đề **hoặc** mô tả, không phân biệt hoa thường (có phân biệt dấu) |
| `sort` | `NEWEST` (mặc định), `VIEWS`, `LIKES`, `COMMENTS` | giảm dần; trùng số thì video mới hơn trước |

Mọi tham số đều tuỳ chọn. Giá trị enum sai → 400.

### 3.6 `DELETE /{videoId}`
**Bắt buộc token.** Xoá mềm video của chính mình. Trả `200 OK`, `data` null.

Lỗi: `VIDEO_NOT_FOUND` (404 — id không tồn tại hoặc đã xoá), `NOT_VIDEO_OWNER` (403 — video tồn tại nhưng của người khác), `401`.

Xoá mềm nên video biến mất khỏi mọi endpoint đọc ngay lập tức, và **kết quả transcode đến sau sẽ bị bỏ qua** thay vì làm video sống lại. Với chủ video, xoá là một chiều — không có API khôi phục cho client (chỉ admin khôi phục được trong thời gian giữ thùng rác).

Ở các service khác thì **không tức thì**: `200 OK` chỉ nói video-service đã ghi xong. `VideoDeletedEvent` đi qua Kafka trong vòng ~5 giây (chu kỳ outbox poll), rồi search-service mới xoá document và recommendation-service mới gỡ video khỏi trending cùng các tag index. Trong khoảng đó video vẫn có thể xuất hiện ở kết quả tìm kiếm hoặc trong feed đề xuất, và bấm vào sẽ nhận `VIDEO_NOT_FOUND`. Client nên tự ẩn video vừa xoá ở phía mình thay vì đợi list load lại.

File trên MinIO (bản gốc, thumbnail, và toàn bộ output HLS) **không** bị xoá ngay: media-worker chỉ cách ly (không còn đọc công khai theo URL), và giữ trong thùng rác `video.trash.retention` (mặc định 30 ngày) để admin còn xem lại. Hết hạn, video-service phát `VideoPurgedEvent` và media-worker mới xoá hẳn. Video bị xoá trước khi kịp công bố vẫn phát cả `VideoDeletedEvent` lẫn `VideoPurgedEvent` — file raw đã nằm trên MinIO.

### 3.7 `PATCH /{videoId}/visibility` — đổi ai được xem
**Bắt buộc token, chỉ chủ video.** Request `{ "visibility": "PUBLIC" | "FRIENDS" | "PRIVATE" }` (bắt buộc). Trả `200 OK`, `data` → `VideoResponse` đã cập nhật.

Có hiệu lực ngay ở video-service. Search, feed đề xuất và trending nhận `VideoVisibilityChangedEvent` qua outbox (~5 giây), nên chuyển sang `PRIVATE` thì video còn có thể lộ ở đó vài giây.

Lỗi: `VALIDATION_ERROR` (400 — thiếu/sai giá trị), `NOT_VIDEO_OWNER` (403), `VIDEO_NOT_FOUND` (404 — không tồn tại hoặc đã xoá), `401`.

### 3.8 `PATCH /{videoId}/comments-setting` — bật/tắt comment của một video
**Bắt buộc token, chỉ chủ video.** Request `{ "disabled": true }` (bắt buộc). Trả `200 OK`, `data` → `VideoResponse` với `commentsDisabled` mới. Đây là công tắc "Allow comments" của chủ video; interaction-service đọc cờ này mỗi lần có comment, nên có hiệu lực ngay. Comment cũ không bị xoá, chỉ bị ẩn (`GET /comments` trả trang rỗng).

Lỗi: như 3.7.

### 3.9 `GET /users/stats/batch?ids=1,2,3` — số liệu profile của nhiều người
Không cần token. Trả `data` → `UserVideoStatsResponse[]` (`userId, videoCount, totalLikes, totalViews`), cùng con số công khai như `GET /users/{userId}/stats`. Tối đa 50 id (`max-page-size`), id trùng bị gộp.

## 4. Enums

### `VideoStatus`
| Giá trị | Ý nghĩa với client |
|---|---|
| `PROCESSING` | Vừa đăng, đang transcode. Chưa có `hlsUrl`/`thumbnailUrl`/`previewUrl`, chưa lên feed. Hiện spinner/placeholder. |
| `PUBLISHED` | Phát được, đã có `hlsUrl`. Trạng thái duy nhất xuất hiện trên `/feed`. |
| `FAILED` | Transcode hỏng, hoặc file bị từ chối vì vượt 500 MB / 10 phút. `failureReason` mang lý do (xem mục 3.2). Chỉ chủ video nhìn thấy; UI nên hiện `failureReason` và cho xoá + đăng lại. |
| `PENDING_MODERATION` | Transcode xong, đang chờ kiểm duyệt tự động (thường vài giây). Chỉ chủ video thấy. Vẫn hiện "đang xử lý". |
| `PENDING_REVIEW` | Kiểm duyệt tự động không chắc chắn (hoặc không chạy được), hoặc tiêu đề/mô tả/tag bị giữ lại (mục 3.2) — chờ admin duyệt. Chỉ chủ video thấy. Hiện "đang chờ duyệt". |
| `REJECTED` | Bị kiểm duyệt tự động từ chối. Chỉ chủ video thấy. Admin có thể khôi phục → `PUBLISHED`. |
| `TAKEN_DOWN` | Bị admin gỡ vì vi phạm. Chỉ chủ video nhìn thấy. Nếu được khôi phục, video quay lại **đúng trạng thái trước khi gỡ** (không mặc định thành `PUBLISHED`) — client đừng tự đoán trạng thái sau khôi phục, cứ đọc lại từ server. |

`status` chỉ do server đổi (qua sự kiện transcode/moderation), **không có API cho client đổi**. `visibility` thì chủ video đổi được qua mục 3.7.

### `VideoVisibility`
`PUBLIC` | `FRIENDS` | `PRIVATE`. Đặt lúc đăng, chủ video đổi được bằng mục 3.7. `PUBLIC` = ai cũng xem (trừ khi tài khoản chủ riêng tư — xem mục 3.4); `FRIENDS` = chỉ người follow lẫn nhau với chủ, không bao giờ lên feed; `PRIVATE` = chỉ chủ video (mục 3.4, 3.5).

## 5. Counter (`viewCount` / `likeCount` / `commentCount`)

Ba con số này được cập nhật **bất đồng bộ qua Kafka**, không phải trong request like/comment/view. Sau khi user bấm like ở `interaction-service`, `likeCount` trong `VideoResponse` có thể còn giá trị cũ trong một khoảng ngắn.

Phía Flutter: **cập nhật lạc quan (optimistic) trên UI** ngay khi bấm, và coi số từ video-service là nguồn để đồng bộ lại khi load lại màn hình — đừng gọi `GET /{videoId}` ngay sau khi like để lấy số mới, sẽ ra số cũ và làm UI nhảy ngược.

### `viewCount` — client phải chủ động báo

Server không tự biết ai đang xem. `viewCount` chỉ nhích khi client gọi **`POST /api/v1/interactions/videos/{videoId}/view`** (interaction-service, **bắt buộc token**). Không gọi thì số này đứng yên mãi mãi.

- Gọi **một lần mỗi lần bắt đầu xem**, không gọi theo tick thời gian. Body là `{ "playId": "<UUID client sinh khi bắt đầu phát>" }` — mỗi lần phát một `playId` mới, nên **xem lại vẫn được tính**, giống TikTok.
- Response: `{ "videoId": ..., "counted": true, "viewCount": 12 }`. `counted: false` nghĩa là "`playId` này đã được tính rồi" (tức là một lần retry), không phải lỗi.
- Xem ẩn danh **không được tính** — không có token thì không có danh tính để áp giới hạn 60 lần phát/giờ.
- Số `viewCount` trong response trên là của interaction-service. Số trong `VideoResponse` của video-service đến sau qua Kafka nên trễ hơn một chút.

## 6. Bảng mã lỗi (`code`) đầy đủ

| `code` | HTTP status | Khi nào xảy ra |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `title` rỗng sau khi làm sạch/quá 150 ký tự, `description` quá 2000, tag quá 50, `visibility` thiếu hoặc sai giá trị enum, `rawFileUrl` sai scheme/host/bucket (mục 3.2) |
| `UNSUPPORTED_UPLOAD_TYPE` | 400 | `contentType` ở mục 3.1 không phải `video/mp4`, `video/quicktime` hoặc `video/webm` |
| `FOREIGN_UPLOAD` | 400 | `rawFileUrl` trỏ vào file của tài khoản khác, hoặc vào key không do mục 3.1 cấp. Chỉ publish được đúng `fileUrl` mà chính token này vừa nhận |
| `UPLOAD_NOT_FOUND` | 400 | Chưa POST file lên `uploadUrl` (mục 3.1), hoặc file đã hết hạn sau 7 ngày. Upload lại rồi mới gọi `POST /` |
| `RAW_FILE_ALREADY_PUBLISHED` | 400 | `rawFileUrl` này đã có video rồi. Thường là retry sau timeout — lần trước đã thành công |
| `INVALID_FEED_CURSOR` | 400 | `cursor` ở mục 3.3 không phải do server cấp. Bỏ cursor đang giữ, tải lại feed từ đầu |
| `TOO_MANY_FOLLOWED_USERS` | 400 | `followedUserIds` ở mục 3.3b quá 500 id. Cắt danh sách following theo trang thay vì gửi hết một lần |
| `NOT_VIDEO_OWNER` | 403 | `DELETE` video của người khác |
| `VIDEO_TITLE_REJECTED` | 422 | `title` chứa từ ngữ bị cấm hẳn; không lưu gì, sửa rồi gửi lại cùng `rawFileUrl` (mục 3.2) |
| `VIDEO_DESCRIPTION_REJECTED` | 422 | Như trên, cho `description` |
| `VIDEO_TAGS_REJECTED` | 422 | Như trên, cho tag (đọc liền nhau, nên một cụm bị cấm tách ra nhiều tag vẫn bị bắt) |
| `PRIVATE_ACCOUNT` | 403 | Lưới video (mục 3.5) của tài khoản riêng tư mà người gọi không follow |
| `VIDEO_NOT_FOUND` | 404 | Không tồn tại, đã xoá, hoặc không có quyền xem (server gộp 4 case, xem mục 3.4) |
| `INTERNAL_ERROR` | 500 | Lỗi không xác định |
| `UPLOAD_URL_UNAVAILABLE` | 503 | Không ký được presigned URL ở mục 3.1. Cấu hình storage sai hoặc storage chết — retry được, nhưng sửa request không giúp gì |

Ngoài ra `401 Unauthorized` (không có `code` riêng của video-service, đến từ `security-lib`/gateway) xảy ra ở `POST`/`DELETE` khi thiếu/hết hạn/token bị thu hồi — xử lý giống auth-service doc (thử `/refresh` **một lần** rồi mới logout local).

Ở các `GET` public: không gửi token thì server coi bạn là khách vãng lai và giấu bớt video (PROCESSING/PRIVATE của chính mình). Gửi token **hết hạn** thì gateway trả `401` như mọi path khác — refresh một lần rồi gửi lại, đừng coi đó là lỗi của `GET`. (Gọi thẳng `video-service:8083`, bỏ qua gateway, thì token hết hạn mới bị hạ xuống khách.)

## 7. Rate limiting

- Rate-limit **ở tầng gateway** (`:8080`, theo IP — 20 request/giây, burst 40, dùng chung toàn bộ API).
- Giới hạn theo IP là lý do phải poll giãn dần ở mục 3.2: nhiều thiết bị sau cùng một NAT dùng chung hạn mức này.
- Không có giới hạn theo tài khoản cho việc upload: `POST /upload-url` và `POST /` không bao giờ trả `429` riêng của video-service.

## 8. Sự kiện Kafka (Events)

Phần này cho người tích hợp backend, không phải client mobile. `video-service` vừa phát vừa tiêu thụ event. Bảng đầy đủ toàn hệ thống: `docs/ARCHITECTURE.md` §5c.

### Phát (producer) — `VideoEventPublisher`

Cả bốn loại đi chung topic **`video.video-events`**, key = `videoId`, nên Kafka đảm bảo thứ tự publish→delete theo từng video. Dùng **outbox** (cờ per-document trên `Video`, không có collection riêng vì Mongo deployment này không có replica set → không có transaction đa document). Poll mỗi 5s, đánh dấu published **sau** khi broker ack (`OutboxDispatcher` của `kafka-lib`).

| eventType header | Event | Payload | Khi nào |
|---|---|---|---|
| `VideoPublishedEvent` | `VideoPublishedEvent` | `eventId, occurredAt, videoId, userId, title, description, rawFileUrl, visibility, tags[], textVerdict` | Sau `POST /` — `eventId` derive từ `videoId` (publish đúng 1 lần dù outbox retry) |
| `VideoDeletedEvent` | `VideoDeletedEvent` | `eventId, occurredAt, videoId, userId, rawFileUrl` | Ngay sau `DELETE /{id}` — gỡ video khỏi search và feed đề xuất; media-worker chỉ cách ly media |
| `VideoPurgedEvent` | `VideoPurgedEvent` | `eventId, occurredAt, videoId, userId, rawFileUrl` | Khi hết `video.trash.retention` (mặc định 30 ngày) kể từ lúc xoá — chỉ media-worker xử lý, xoá hẳn media. Phát cả cho video bị xoá trước khi kịp công bố |
| `VideoVisibilityChangedEvent` | `VideoVisibilityChangedEvent` | `eventId, occurredAt, videoId, userId, visibility` (audience thực tế, xem dưới) | Sau `PATCH /{id}/visibility`, hoặc khi tài khoản chủ đổi riêng tư/công khai trong lúc video đang publish. Chỉ gửi sau khi `VideoPublishedEvent` của video đó đã được ack |

`description` và `tags` được mang trên `VideoPublishedEvent` vì `search-service`/`recommendation-service` không có đường đọc vào Mongo của video-service. `tags` không bao giờ null (list rỗng).

`textVerdict` là kết quả kiểm duyệt chữ (mục 3.2): `APPROVED`, `REVIEW`, hoặc `null` (video cũ, hoặc `moderation.text.enforce=false`). media-worker chép nó sang `VideoTranscodedEvent.textVerdict` rồi gộp vào verdict kiểm duyệt hình — hình `APPROVED` + chữ `REVIEW` ra `VideoModerationCompletedEvent` với `REVIEW` — nên mọi nơi đọc verdict (status ở đây, search, recommendation, chat) giữ video lại như nhau. Xem `docs/moderation.md` §Text. Chi tiết (cờ nào, trường nào) lưu ở `video.textModeration`, chỉ trả về trên đường đọc của admin.

`visibility` trên `VideoPublishedEvent` và `VideoVisibilityChangedEvent` là **audience thực tế**, không phải giá trị lưu: video `PUBLIC` của tài khoản riêng tư đi ra là `FOLLOWERS`. Mọi consumer giữ bản sao (search, recommendation, chat) coi mọi thứ khác `PUBLIC` là ẩn, nên video đó rời khỏi tìm kiếm, For You và trending mà các service kia không phải sửa gì. `VideoResponse.visibility` vẫn là giá trị lưu (`PUBLIC`/`FRIENDS`/`PRIVATE`) để chủ video thấy đúng lựa chọn của mình. chat-service chuyển nguyên chữ này xuống trình duyệt trong frame `state` — client phải hiểu `FOLLOWERS` là "không công khai" và không ghi nó vào công tắc visibility của chủ video.

`VideoVisibilityChangedEvent` chỉ được gửi sau khi `VideoPublishedEvent` của chính video đó đã được ack: hai poll chạy trên hai thread riêng, và không có điều kiện này thì event visibility có thể tới partition trước chính video.

Consumer của `video.video-events`: media-worker, search-service, recommendation-service. (analytics-service cũng nghe — ngoài phạm vi doc này.)

### Tiêu thụ (consumer) — cập nhật counter & trạng thái

Mọi consumer idempotent qua `IdempotentEventProcessor` (bảng `processed_events`, TTL index). Counter cập nhật bằng `$inc` nguyên tử (không load-modify-save) và **bỏ qua video `deletedAt != null`**; `unlike`/`comment deleted` còn thêm điều kiện `count > 0` (chống `$inc` âm vĩnh viễn). Video `TAKEN_DOWN` **vẫn được đếm**: restore đưa nó trở lại, và interaction-service đếm xuyên qua takedown — unlike và xoá comment không cần video hiển thị, còn like gửi ngay trước takedown có thể tới sau nó. Bỏ qua chúng làm video sau restore hiện số like/comment cao hơn thật.

| Topic | Event | Tác dụng |
|---|---|---|
| `media.video-transcoded-events` | `VideoTranscodedEvent` | `success` → `status = PUBLISHED` + `hlsUrl`/`thumbnailUrl`/`previewUrl`/`durationSeconds`; ngược lại `status = FAILED` + `failureReason`. Qua `VideoStateUpdater` (re-read nếu có takedown chen giữa) |
| `interaction.like-events` | `VideoLikeEvent` | `likeCount += liked ? 1 : -1` |
| `interaction.comment-events` | `CommentCreatedEvent` / `CommentDeletedEvent` (route theo header; vắng ⇒ Created) | `commentCount ± 1` |
| `interaction.view-events` | `VideoViewedEvent` | `viewCount += 1` (chỉ view đã qua dedupe của interaction-service) |
| `admin.moderation-events` | `VideoTakenDownEvent` / `VideoRestoredEvent` (route theo header; **bắt buộc** có header, thiếu → drop + warn) | `status = TAKEN_DOWN` / restore: video đang TAKEN_DOWN thì về trạng thái trước takedown; đang `PENDING_REVIEW`/`REJECTED` thì là admin duyệt → `PUBLISHED`; trạng thái khác (PROCESSING, FAILED, PENDING_MODERATION, PUBLISHED) thì không làm gì |
| `user.privacy-events` | `UserPrivacyChangedEvent` | Ghi `owner_privacy` (`_id = userId`) nếu event không cũ hơn bản đang có; đánh dấu `ownerPrivate` trên mọi video chưa xoá của user, xếp hàng `VideoVisibilityChangedEvent` cho từng video, xoá cache. Event cũ tới sau (outbox của user-service gửi lại) không làm gì. Consumer này **không** claim qua `IdempotentEventProcessor` (mọi bước đều là phép gán, chạy lại vô hại), và lỗi thì retry mỗi 5s cho tới khi thành công chứ không vào DLT — mất một lần chuyển sang riêng tư là video lộ ra cho người lạ. Chỉ payload không parse được mới vào `user.privacy-events.DLT` |

`kafka-lib` error handler: retry 3 lần → `<topic>.DLT`.

## 9. Những điều KHÔNG nên làm phía Flutter

- Không parse `id` của video thành `int` — Snowflake 19 chữ số, sai âm thầm trên Flutter Web. Luôn dùng `String`.
- Không coi `201` của `POST /` là "video đã đăng xong" — phải poll tới `PUBLISHED` mới có `hlsUrl` để phát (mục 3.2).
- Không poll `GET /{videoId}` với chu kỳ cố định 1s — giãn dần và có điểm dừng, nếu không sẽ đụng rate-limit của gateway cho cả những request khác của app.
- Không quên gửi token ở các `GET` khi user đã đăng nhập — sẽ mất video `PRIVATE`/`PROCESSING` của chính họ mà không có lỗi nào báo (mục 3.4, 3.5).
- Không suy luận "video không tồn tại" từ `404` — server cố tình gộp cả trường hợp không có quyền xem.
- Không append thẳng trang mới vào list khi infinite scroll — khử trùng lặp theo `id`, vì phân trang theo offset trên feed đang thay đổi sẽ trả lại item cũ (mục 2).
- Không tự build `rawFileUrl` phía client — chỉ dùng URL trả về từ luồng upload thật, server chặn theo allow-list host/bucket.
- Không hiển thị `likeCount`/`commentCount` từ response như kết quả tức thì của thao tác vừa làm — dùng optimistic update (mục 5).
- Không gọi thẳng `video-service:8083` từ production build — luôn qua gateway `:8080`.
