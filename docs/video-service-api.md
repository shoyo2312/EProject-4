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
- `/feed` — phân trang theo **cursor**, không có `page`/`totalElements`/`totalPages`. Xem mục 3.3.

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
**Bắt buộc token.** Trả `200 OK`. Đây là bước 1 của việc đăng video: server ký sẵn một URL, client `PUT` file thẳng lên object storage bằng URL đó, **bytes không đi qua video-service**. Đăng video (mục 3.2) là bước 2.

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
  "uploadUrl": "http://localhost:9000/video-media/raw/123/7312458901234567.mp4?X-Amz-Signature=...",
  "fileUrl": "s3://video-media/raw/123/7312458901234567.mp4",
  "expiresInSeconds": 900
}
```

Luồng đầy đủ phía client:
1. `POST /upload-url` → nhận `uploadUrl` + `fileUrl`.
2. `PUT <uploadUrl>` với body là bytes của file, header `Content-Type` đúng loại đã khai. **Không** gửi kèm `Authorization` — chữ ký đã nằm trong query string, thêm header Authorization sẽ làm S3 từ chối.
3. `POST /` (mục 3.2) với `rawFileUrl` = **`fileUrl`**, không phải `uploadUrl`.

> **Dùng `fileUrl`, không dùng `uploadUrl`, cho `rawFileUrl`.** `uploadUrl` chứa chữ ký hết hạn sau `expiresInSeconds` (mặc định 15 phút) và sẽ trượt allow-list ở mục 3.2. `fileUrl` là vị trí thật của file, media-worker đọc nó có thể hàng giờ sau.

> **URL hết hạn thì xin lại, đừng cache.** Quá `expiresInSeconds` mà chưa `PUT` xong → S3 trả 403, gọi lại `/upload-url` để lấy URL mới (key mới, không ghi đè cái cũ).

Không có bước "báo upload xong": nếu client bỏ ngang giữa chừng thì chỉ còn một object mồ côi trong bucket, không có bản ghi Video nào được tạo. Object mồ côi tự hết hạn sau 7 ngày — bucket có lifecycle rule trên prefix `raw/` (khai ở service `minio-init` trong `docker-compose.yml`). Nghĩa là **đã `PUT` xong thì gọi `POST /` (mục 3.2) trong vòng 7 ngày**, quá hạn file biến mất dù `fileUrl` vẫn còn trong tay client.

Lỗi: `UNSUPPORTED_UPLOAD_TYPE` (400) khi `contentType` ngoài 3 loại trên, `VALIDATION_ERROR` (400) khi thiếu `contentType`, `401` (thiếu/hết hạn token), `UPLOAD_URL_UNAVAILABLE` (503) khi server không ký được URL — lỗi phía server, không phải request sai.

### 3.2 `POST /` — đăng video
**Bắt buộc token.** Trả `201 Created`.

Request (`CreateVideoRequest`):
```json
{
  "title": "Tiêu đề",                                // bắt buộc, tối đa 150 ký tự, không được toàn khoảng trắng
  "description": "Mô tả",                             // optional, tối đa 2000 ký tự
  "rawFileUrl": "s3://video-media/raw/123456789012345/7312.mp4",  // bắt buộc, tối đa 500 ký tự, xem ràng buộc bên dưới
  "visibility": "PUBLIC",                             // bắt buộc: PUBLIC | PRIVATE
  "tags": ["dance", "food"]                           // optional, tối đa 10 tag, mỗi tag tối đa 50 ký tự
}
```

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
  "hlsUrl": null,                 // null cho tới khi transcode xong
  "durationSeconds": null,        // null cho tới khi transcode xong
  "status": "PROCESSING",
  "visibility": "PUBLIC",
  "viewCount": 0,
  "likeCount": 0,
  "commentCount": 0,
  "tags": ["dance", "food"],      // đã chuẩn hoá, có thể rỗng nhưng không bao giờ null
  "createdAt": "2026-08-12T10:00:00Z"
}
```

> **`id` là `String`, không phải `int`.** Khai báo `String id` trong model Dart. Đây là Snowflake id 19 chữ số — parse thành số vẫn vừa `int` 64-bit của Dart native, nhưng **tràn trên Flutter Web** (JS number chỉ chính xác tới 2^53) và sẽ sai âm thầm. Riêng `userId` thì server trả về dạng số.

> **`201` KHÔNG có nghĩa là video xem được.** Video vào trạng thái `PROCESSING`, chưa có `hlsUrl` để phát, và **chưa xuất hiện trên `/feed`**. Việc transcode chạy bất đồng bộ qua Kafka, mất từ vài giây tới vài phút. Client phải poll `GET /{videoId}` cho tới khi `status` là `PUBLISHED` (phát được) hoặc `FAILED` (hỏng, báo user đăng lại). Poll giãn dần (vd. 2s → 5s → 10s, dừng sau ~5 phút và hiện "đang xử lý, quay lại sau") thay vì poll dày — gateway giới hạn 20 request/giây theo IP.

Lỗi: `VALIDATION_ERROR` (400), `401` (thiếu/hết hạn token).

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

Chỉ trả video **`PUBLISHED` + `PUBLIC`** và chưa bị xoá. Video của chính mình đang `PROCESSING`/`PRIVATE` **không** xuất hiện ở đây kể cả khi có gửi token — muốn xem video của mình thì dùng mục 3.5.

Lỗi: `INVALID_FEED_CURSOR` (400 — cursor không phải do server này cấp, thường do client tự sửa hoặc lưu nhầm). Xử lý: bỏ cursor đang giữ và tải lại từ đầu.

### 3.4 `GET /{videoId}`
Không bắt buộc token, **nhưng nên gửi nếu đã đăng nhập**. Trả `200 OK`, `data` → `VideoResponse`.

Quy tắc hiển thị:
- **Chủ video** (token khớp `userId` của video): xem được ở mọi trạng thái — `PROCESSING`, `PRIVATE`, `FAILED`, `TAKEN_DOWN`.
- **Người khác / không có token**: chỉ xem được video `PUBLISHED` + `PUBLIC`.

Lỗi: `VIDEO_NOT_FOUND` (404) — dùng chung cho **cả 4 trường hợp**: id không tồn tại, video đã bị xoá, video `PRIVATE` của người khác, hoặc video chưa/không còn `PUBLISHED` (đang `PROCESSING`, `FAILED`, hoặc bị `TAKEN_DOWN`). Server **cố tình** không phân biệt để không lộ sự tồn tại của video riêng tư. Hệ quả phía client: **404 ở đây không chứng minh video không tồn tại** — nếu đang poll sau khi upload mà nhận 404, khả năng cao là quên gửi token chứ không phải video biến mất.

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

Không có lỗi `USER_NOT_FOUND` — `userId` không tồn tại chỉ trả về trang rỗng (`content: []`), vì video-service không giữ dữ liệu user.

### 3.6 `DELETE /{videoId}`
**Bắt buộc token.** Xoá mềm video của chính mình. Trả `200 OK`, `data` null.

Lỗi: `VIDEO_NOT_FOUND` (404 — id không tồn tại hoặc đã xoá), `NOT_VIDEO_OWNER` (403 — video tồn tại nhưng của người khác), `401`.

Xoá mềm nên video biến mất khỏi mọi endpoint đọc ngay lập tức, và **kết quả transcode đến sau sẽ bị bỏ qua** thay vì làm video sống lại. Xoá là một chiều, không có API khôi phục.

Ở các service khác thì **không tức thì**: `200 OK` chỉ nói video-service đã ghi xong. `VideoDeletedEvent` đi qua Kafka trong vòng ~5 giây (chu kỳ outbox poll), rồi search-service mới xoá document và recommendation-service mới gỡ video khỏi trending cùng các tag index. Trong khoảng đó video vẫn có thể xuất hiện ở kết quả tìm kiếm hoặc trong feed đề xuất, và bấm vào sẽ nhận `VIDEO_NOT_FOUND`. Client nên tự ẩn video vừa xoá ở phía mình thay vì đợi list load lại.

Video bị xoá **trong vòng ~5 giây đầu sau khi publish** có thể chưa từng được công bố ra ngoài; khi đó không có `VideoDeletedEvent` nào cả, vì không service nào từng biết tới nó.

File trên MinIO (bản gốc, thumbnail, và toàn bộ output HLS) do media-worker xoá khi nhận event này. Không có API nào để lấy lại.

## 4. Enums

### `VideoStatus`
| Giá trị | Ý nghĩa với client |
|---|---|
| `PROCESSING` | Vừa đăng, đang transcode. Chưa có `hlsUrl`/`thumbnailUrl`, chưa lên feed. Hiện spinner/placeholder. |
| `PUBLISHED` | Phát được, đã có `hlsUrl`. Trạng thái duy nhất xuất hiện trên `/feed`. |
| `FAILED` | Transcode hỏng. Chỉ chủ video nhìn thấy; UI nên cho xoá và đăng lại. |
| `TAKEN_DOWN` | Bị admin gỡ vì vi phạm. Chỉ chủ video nhìn thấy. Nếu được khôi phục, video quay lại **đúng trạng thái trước khi gỡ** (không mặc định thành `PUBLISHED`) — client đừng tự đoán trạng thái sau khôi phục, cứ đọc lại từ server. |

`status` chỉ do server đổi (qua sự kiện transcode/moderation), **không có API cho client đổi**. Client cũng không đổi được `visibility` sau khi đăng — chưa có endpoint update.

### `VideoVisibility`
`PUBLIC` | `PRIVATE`. Đặt lúc đăng và cố định. `PRIVATE` = chỉ chủ video xem được (mục 3.4, 3.5).

## 5. Counter (`viewCount` / `likeCount` / `commentCount`)

Ba con số này được cập nhật **bất đồng bộ qua Kafka**, không phải trong request like/comment/view. Sau khi user bấm like ở `interaction-service`, `likeCount` trong `VideoResponse` có thể còn giá trị cũ trong một khoảng ngắn.

Phía Flutter: **cập nhật lạc quan (optimistic) trên UI** ngay khi bấm, và coi số từ video-service là nguồn để đồng bộ lại khi load lại màn hình — đừng gọi `GET /{videoId}` ngay sau khi like để lấy số mới, sẽ ra số cũ và làm UI nhảy ngược.

### `viewCount` — client phải chủ động báo

Server không tự biết ai đang xem. `viewCount` chỉ nhích khi client gọi **`POST /api/v1/interactions/videos/{videoId}/view`** (interaction-service, **bắt buộc token**). Không gọi thì số này đứng yên mãi mãi.

- Gọi **một lần mỗi lần bắt đầu xem**, không gọi theo tick thời gian. Gọi thừa cũng không sai: interaction-service khử trùng lặp theo từng người xem trong **24 giờ**, lần thứ hai của cùng user trả `counted: false` và không đổi số.
- Response: `{ "videoId": ..., "counted": true, "viewCount": 12 }`. `counted: false` nghĩa là "hôm nay bạn đã được tính cho video này rồi", không phải lỗi.
- Xem ẩn danh **không được tính** — không có token thì không có danh tính để khử trùng lặp.
- Số `viewCount` trong response trên là của interaction-service. Số trong `VideoResponse` của video-service đến sau qua Kafka nên trễ hơn một chút.

## 6. Bảng mã lỗi (`code`) đầy đủ

| `code` | HTTP status | Khi nào xảy ra |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `title` rỗng/quá 150 ký tự, `description` quá 2000, `visibility` thiếu hoặc sai giá trị enum, `rawFileUrl` sai scheme/host/bucket (mục 3.2) |
| `UNSUPPORTED_UPLOAD_TYPE` | 400 | `contentType` ở mục 3.1 không phải `video/mp4`, `video/quicktime` hoặc `video/webm` |
| `FOREIGN_UPLOAD` | 400 | `rawFileUrl` trỏ vào file của tài khoản khác, hoặc vào key không do mục 3.1 cấp. Chỉ publish được đúng `fileUrl` mà chính token này vừa nhận |
| `RAW_FILE_ALREADY_PUBLISHED` | 400 | `rawFileUrl` này đã có video rồi. Thường là retry sau timeout — lần trước đã thành công |
| `INVALID_FEED_CURSOR` | 400 | `cursor` ở mục 3.3 không phải do server cấp. Bỏ cursor đang giữ, tải lại feed từ đầu |
| `NOT_VIDEO_OWNER` | 403 | `DELETE` video của người khác |
| `VIDEO_NOT_FOUND` | 404 | Không tồn tại, đã xoá, hoặc không có quyền xem (server gộp 4 case, xem mục 3.4) |
| `INTERNAL_ERROR` | 500 | Lỗi không xác định |
| `UPLOAD_URL_UNAVAILABLE` | 503 | Không ký được presigned URL ở mục 3.1. Cấu hình storage sai hoặc storage chết — retry được, nhưng sửa request không giúp gì |

Ngoài ra `401 Unauthorized` (không có `code` riêng của video-service, đến từ `security-lib`/gateway) xảy ra ở `POST`/`DELETE` khi thiếu/hết hạn/token bị thu hồi — xử lý giống auth-service doc (thử `/refresh` **một lần** rồi mới logout local).

Lưu ý khác biệt ở các `GET`: token hết hạn gửi kèm `GET` **không** gây 401, chỉ khiến server coi bạn là khách vãng lai và giấu bớt video. Nếu màn "Video của tôi" đột nhiên trống, kiểm tra token trước khi nghi dữ liệu.

## 7. Rate limiting

- Chỉ có rate-limit **ở tầng gateway** (`:8080`, theo IP — 20 request/giây, burst 40, dùng chung toàn bộ API).
- **Không có** giới hạn riêng theo tài khoản ở video-service (không giới hạn số video đăng mỗi ngày).
- Giới hạn theo IP là lý do phải poll giãn dần ở mục 3.2: nhiều thiết bị sau cùng một NAT dùng chung hạn mức này.

## 8. Những điều KHÔNG nên làm phía Flutter

- Không parse `id` của video thành `int` — Snowflake 19 chữ số, sai âm thầm trên Flutter Web. Luôn dùng `String`.
- Không coi `201` của `POST /` là "video đã đăng xong" — phải poll tới `PUBLISHED` mới có `hlsUrl` để phát (mục 3.2).
- Không poll `GET /{videoId}` với chu kỳ cố định 1s — giãn dần và có điểm dừng, nếu không sẽ đụng rate-limit của gateway cho cả những request khác của app.
- Không quên gửi token ở các `GET` khi user đã đăng nhập — sẽ mất video `PRIVATE`/`PROCESSING` của chính họ mà không có lỗi nào báo (mục 3.4, 3.5).
- Không suy luận "video không tồn tại" từ `404` — server cố tình gộp cả trường hợp không có quyền xem.
- Không append thẳng trang mới vào list khi infinite scroll — khử trùng lặp theo `id`, vì phân trang theo offset trên feed đang thay đổi sẽ trả lại item cũ (mục 2).
- Không tự build `rawFileUrl` phía client — chỉ dùng URL trả về từ luồng upload thật, server chặn theo allow-list host/bucket.
- Không hiển thị `likeCount`/`commentCount` từ response như kết quả tức thì của thao tác vừa làm — dùng optimistic update (mục 5).
- Không gọi thẳng `video-service:8083` từ production build — luôn qua gateway `:8080`.
