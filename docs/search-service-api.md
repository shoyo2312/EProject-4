# Search Service — API Contract cho Flutter Mobile

Tài liệu này mô tả **những gì client (Flutter/Dart) cần biết** để tích hợp đúng với `search-service`: tìm video (theo từ khoá hoặc hashtag) và tìm sản phẩm. Không phải tài liệu thiết kế backend — chỉ phần hợp đồng API (contract). Đọc kèm `docs/video-service-api.md` vì kết quả tìm video **không đủ để phát** — xem mục 4.

Quy tắc token: **mọi endpoint đều public, không cần token.** Gửi token cũng không đổi kết quả (khác `video-service`/`interaction-service`). Gateway route `GET /api/v1/search/**` là public.

## 1. Tech stack liên quan đến client

| Thành phần | Công nghệ |
|---|---|
| Giao thức | REST/HTTP, JSON — chỉ `GET` |
| Auth | Không |
| Entry point cho mobile | **api-gateway** (`:8080`), KHÔNG gọi thẳng `search-service:8095` |
| Base URL (qua gateway) | `http://<gateway-host>:8080/api/v1/search` |
| Docs tương tác | `http://localhost:8095/swagger-ui.html` (chạy trực tiếp service khi dev) |

Kho dữ liệu phía server là Elasticsearch. Index được dựng **hoàn toàn từ Kafka event** (video published/deleted/transcoded, like, comment, share, product created) — service này **không có database riêng và không đọc database của service khác**. Hệ quả cho client: kết quả tìm kiếm **trễ vài giây** so với thao tác thật (đăng video, like…), và một video vừa xoá có thể còn xuất hiện trong ~5 giây. Xem mục 5.

## 2. Response envelope & phân trang

Mọi response bọc trong `ApiResponse<T>` như các service khác. `data` là một object phân trang kiểu **Spring `Page`** (khác với `PageResponse` rút gọn của user-service — ở đây trả nguyên cấu trúc `Page` của Spring Data):

```json
{
  "success": true,
  "data": {
    "content": [ /* VideoSearchResponse[] hoặc ProductSearchResponse[] */ ],
    "pageable": { "pageNumber": 0, "pageSize": 20 },
    "totalElements": 137,
    "totalPages": 7,
    "number": 0,
    "size": 20,
    "numberOfElements": 20,
    "first": true,
    "last": false,
    "empty": false
  },
  "timestamp": "2026-09-03T10:00:00Z"
}
```

Phân trang bằng query param chuẩn Spring: `?page=0&size=20&sort=field,dir`.

- `page` bắt đầu từ **0**. Không truyền → `page=0`, `size=20`.
- **Không có chặn trên cho `size` ở service này** (khác video-service kẹp 50). Xin 20–50 là hợp lý; xin vài nghìn thì tự làm chậm app mình và tốn hạn mức gateway.
- Điều kiện dừng infinite scroll: `last == true` (hoặc `number + 1 >= totalPages`). `content` ngắn hơn `size` ở trang cuối là bình thường.
- `sort` mặc định theo **điểm liên quan (relevance)** của Elasticsearch khi có `q`; không truyền `q` thì thứ tự không xác định — nên truyền `sort=createdAt,desc` nếu muốn thứ tự ổn định cho danh mục.

Phía Dart: có thể tái dùng wrapper `PageResponse<T>` bằng cách đọc đúng 4 field `content` / `number` / `totalElements` / `totalPages`, bỏ qua phần còn lại.

## 3. Endpoints

Base path: `/api/v1/search`.

### 3.1 `GET /videos` — tìm video

Query param (tất cả optional, nhưng nên có **ít nhất một** trong `q` / `hashtag`):

| Param | Kiểu | Ý nghĩa |
|---|---|---|
| `q` | string | Từ khoá tự do. Khớp trên `title`, `description`, **và** `tags`. Gõ `dance` (không có `#`) vẫn ra video chỉ nhắc `dance` trong hashtag |
| `hashtag` | string | Lọc **chính xác** theo một hashtag. `#Dance`, `dance`, ` Dance ` đều được — server tự chuẩn hoá (lowercase, bỏ `#`, trim) trước khi so |
| `page`, `size`, `sort` | | Xem mục 2 |

- `q` và `hashtag` **cộng dồn (AND)**: `?q=biển&hashtag=dance` = video vừa khớp "biển" ở text vừa có tag `dance`.
- Không truyền cả hai → trả về mọi video `PUBLISHED` (phân trang). Không phải lỗi, nhưng thường không phải ý client muốn.
- `hashtag` phải **khớp cả tag**: `dance` không khớp video tag `dancing`. Đây là chủ đích (field keyword, không phân tích) — để lọc hashtag chính xác như TikTok.

Chỉ trả về video ở trạng thái **`PUBLISHED`**. Video đang `PROCESSING`, `FAILED`, `TAKEN_DOWN`, `PRIVATE` hoặc đã xoá **không** xuất hiện, kể cả với chủ video (service này không biết ai đang gọi).

Response `data.content[]` → `VideoSearchResponse`:
```json
{
  "id": "7312458901234567",       // Snowflake id dạng CHUỖI — xem lưu ý mục 4
  "userId": 123456789012345,      // số
  "title": "Tiêu đề",
  "description": "Mô tả",         // có thể null (event cũ chưa mang description)
  "thumbnailUrl": "http://localhost:9000/video-media/...",  // null cho tới khi transcode xong
  "status": "PUBLISHED",
  "durationSeconds": 42,          // null cho tới khi transcode xong
  "tags": ["dance", "food"],      // đã chuẩn hoá, có thể rỗng, không bao giờ null
  "viewCount": 1043,
  "likeCount": 42,
  "commentCount": 8,
  "shareCount": 7,
  "createdAt": "2026-09-01T10:00:00Z"
}
```

> **Các counter (`viewCount`/`likeCount`/…) trong kết quả tìm kiếm là bản sao trễ nhất trong hệ thống** — chúng đến từ Kafka event và search-service không có outbox nên có thể lệch vĩnh viễn nếu một event bị mất. Đừng hiển thị chúng như số chính xác; nguồn sự thật là `interaction-service` `GET /counts` (xem `docs/interaction-service-api.md` mục 4).

### 3.2 `GET /products` — tìm sản phẩm

Query param (tất cả optional):

| Param | Kiểu | Ý nghĩa |
|---|---|---|
| `q` | string | Từ khoá tự do, khớp trên `name` và `description` |
| `category` | string | Lọc chính xác theo category |
| `minPrice`, `maxPrice` | số thập phân | Khoảng giá (bao gồm 2 đầu) |
| `page`, `size`, `sort` | | Xem mục 2 |

Chỉ trả sản phẩm `status = ACTIVE`.

Response `data.content[]` → `ProductSearchResponse`:
```json
{
  "id": 987654321098765,          // số (product id, không phải video Snowflake dạng chuỗi)
  "sellerId": 123456789012345,
  "name": "Áo thun",
  "description": "...",           // có thể null
  "price": 199000.00,            // số thập phân — dùng Decimal/String phía Dart, KHÔNG double
  "category": "fashion",
  "imageUrl": "...",             // có thể null
  "createdAt": "2026-09-01T10:00:00Z"
}
```

> Tính năng thương mại điện tử có thể đang tắt ở môi trường dev (`docker-compose` không chạy `product-service`). Khi đó index `products` rỗng và endpoint này luôn trả trang rỗng — không phải lỗi.

## 4. Kết quả tìm video KHÔNG đủ để phát — client phải tự lấy nội dung từ video-service

`VideoSearchResponse` **không có** `hlsUrl`. Không có URL này thì không phát được. Luồng đúng ở client:

1. `GET /api/v1/search/videos?...` → danh sách `id` theo thứ tự relevance.
2. Bỏ những id đã có sẵn trong cache local.
3. Lấy phần còn lại từ `video-service`: `GET /api/v1/videos/batch?ids=...` (tối đa 50 id/lần, xem `docs/video-service-api.md` mục 3.4b).
4. **Hiển thị theo đúng thứ tự bước 1**, không theo thứ tự video-service trả về.

Xử lý sai lệch: một id trong kết quả tìm kiếm có thể đã bị **xoá/takedown** giữa lúc index và lúc client hỏi. `video-service` sẽ bỏ id đó khỏi `batch` (hoặc trả `404` ở `GET /{id}`) — **bỏ qua im lặng**, đừng báo lỗi cho user.

> **`id` là `String`, không phải `int`.** Snowflake 19 chữ số, parse thành số sẽ tràn âm thầm trên Flutter Web. Ghép thẳng vào URL `batch?ids=$id1,$id2`, đừng `int.parse`.

## 5. Độ trễ index — điều client phải lường trước

Mọi thay đổi tới từ Kafka nên có độ trễ:

| Thao tác | Khi nào xuất hiện / biến mất trong kết quả tìm kiếm |
|---|---|
| Đăng video mới | Vào index với `status: PROCESSING` gần như ngay (không lọt kết quả vì chỉ trả `PUBLISHED`); chuyển `PUBLISHED` sau khi transcode xong (`media.video-transcoded-events`) |
| Xoá video | Còn xuất hiện ~5 giây (chu kỳ outbox poll của video-service) rồi biến mất |
| Admin takedown | Không tự biến mất khỏi search ngay — search-service **không** nghe `admin.moderation-events`; document vẫn `PUBLISHED` cho tới khi có event khác đụng vào. Đây là hạn chế đã biết |
| Like/unlike, comment, share | Counter trong `VideoSearchResponse` cập nhật sau vài giây; đừng dựa vào |

Client nên tự ẩn video vừa xoá ở phía mình thay vì đợi search load lại.

## 6. Bảng mã lỗi

| `code` | HTTP | Khi nào |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Param sai kiểu (vd. `minPrice=abc`) |
| `INTERNAL_ERROR` | 500 | Elasticsearch không phản hồi / lỗi không xác định |
| — | 429 | Vượt hạn mức gateway (20 req/s, burst 40, theo IP) |

Không có mã lỗi nghiệp vụ riêng — mọi endpoint đều là đọc và luôn trả `Page`, kể cả rỗng. `q`/`hashtag` không khớp gì → `content: []`, `totalElements: 0`, **không** phải 404.

## 7. Sự kiện Kafka (Events)

search-service **chỉ tiêu thụ**, không phát event nào. Toàn bộ index đến từ đây — mỗi consumer dedupe theo `eventId` (collection `processed_events` trên Elasticsearch).

| Topic | eventType header | Event | Tác dụng lên index |
|---|---|---|---|
| `video.video-events` | `VideoPublishedEvent` (vắng ⇒ coi là Published) | `VideoPublishedEvent` | Tạo `VideoDocument`, `status = PROCESSING`, copy `title`/`description`/`tags` |
| `video.video-events` | `VideoDeletedEvent` | `VideoDeletedEvent` | Xoá hẳn document (không đánh dấu cờ) |
| `media.video-transcoded-events` | — | `VideoTranscodedEvent` | `success` → `status = PUBLISHED` + `thumbnailUrl` + `durationSeconds`; ngược lại `status = FAILED`. Đến trước publication thì kết quả được giữ ở `pendingStatus` và publication nhận lại — document chưa có `status` nên chưa vào kết quả tìm kiếm |
| `interaction.like-events` | — | `VideoLikeEvent` | `likeCount += liked ? 1 : -1` (kẹp ≥ 0) |
| `interaction.view-events` | — | `VideoViewedEvent` | `viewCount++` |
| `interaction.comment-events` | `CommentCreatedEvent` / `CommentDeletedEvent` (vắng ⇒ Created) | `CommentCreatedEvent`, `CommentDeletedEvent` | `commentCount++` / `commentCount--` (kẹp ≥ 0) |
| `interaction.share-events` | — | `VideoSharedEvent` | `shareCount++` |
| `admin.moderation-events` | `VideoTakenDownEvent` / `VideoRestoredEvent` / `ProductSuspendedEvent` / `ProductReactivatedEvent` | 4 event trên | `status = TAKEN_DOWN` / trả về `pendingStatus` / `SUSPENDED` / `ACTIVE`. Event khác trên topic (UserBanned…) bị bỏ qua |
| `product.product-events` | — | `ProductCreatedEvent` | Tạo `ProductDocument` đầy đủ (`name`, `description`, `category`, `imageUrl`, `price`), `status = ACTIVE` |

Mọi consumer **claim `eventId` trước khi xử lý** (`op_type=create` trên index `processed_events`, 409 ⇒ đã xử lý) chứ không đọc-rồi-ghi: trên Elasticsearch một document vừa ghi chưa đọc lại được cho tới lần refresh kế tiếp (mặc định 1s), nên check-then-act để lọt mọi redelivery trong cửa sổ đó. Mọi cập nhật counter là scripted partial update kèm `retry_on_conflict`, không phải đọc-sửa-ghi cả document.

Row `processed_events` được job retention xoá sau 30 ngày (`search.processed-events.retention-days`).

Hạn chế đã biết: search-service không có outbox và không phát event nào, nên một event bị mất phía producer vẫn để lại index lệch vĩnh viễn.

## 8. Những điều KHÔNG nên làm phía Flutter

- Không phát video từ kết quả search trực tiếp — không có `hlsUrl`, phải qua `video-service` (mục 4).
- Không parse `VideoSearchResponse.id` thành `int` — Snowflake, sai âm thầm trên web.
- Không hiển thị counter trong kết quả search như số chính xác — chúng là bản sao trễ nhất hệ thống (mục 3.1).
- Không coi trang rỗng giữa chừng là hết dữ liệu — dùng `last`/`totalPages`.
- Không gọi search cho từng ký tự user gõ — debounce ≥ 300ms, huỷ request cũ; hạn mức gateway theo IP dùng chung toàn app.
- Không cho rằng video đã xoá biến mất tức thì khỏi search — có độ trễ ~5 giây (mục 5).
- Không gọi thẳng `search-service:8095` từ production build — luôn qua gateway `:8080`.
