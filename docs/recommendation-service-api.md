# Recommendation Service — API Contract cho Flutter Mobile

Tài liệu này mô tả **những gì client (Flutter/Dart) cần biết** để dựng màn hình "For You" và "Trending" từ `recommendation-service`. Đọc kèm `docs/auth-service-api.md` để lấy `accessToken`, `docs/video-service-api.md` để đổi id thành video, và `docs/interaction-service-api.md` mục 3.9 — vì **không gọi `POST /watch` thì feed sẽ lặp lại chính những video vừa xem**.

Điểm khác biệt lớn nhất so với các service khác: hai endpoint ở đây **chỉ trả về id video, không trả về nội dung video**. Xem mục 4.

## 1. Tech stack liên quan đến client

| Thành phần | Công nghệ |
|---|---|
| Giao thức | REST/HTTP, JSON |
| Auth | JWT access token, Bearer scheme — bắt buộc với `/feed`, không cần với `/trending` |
| Entry point cho mobile | **api-gateway** (`:8080`), KHÔNG gọi thẳng `recommendation-service:8087` |
| Base URL (qua gateway) | `http://<gateway-host>:8080/api/v1/recommendations` |
| Docs tương tác | `http://localhost:8087/swagger-ui.html` (chạy trực tiếp service khi dev) |

Toàn bộ trạng thái nằm trong Redis và được dựng từ Kafka event (video mới, like, share, comment, phiên xem). Service này **không có database riêng và không đọc database của service khác** — đó là lý do nó không thể trả về tiêu đề hay URL video.

## 2. Response envelope

Giống các service khác:

```json
{
  "success": true,
  "data": [ ],
  "timestamp": "2026-08-20T10:00:00Z"
}
```

## 3. Endpoints

### 3.1 `GET /trending?limit=20` — bảng xếp hạng chung

Không cần token. Cùng một kết quả cho mọi người.

`limit`: mặc định `20`, bị kẹp về khoảng `1..100`. Gửi `limit=500` thì nhận `100`, không phải lỗi.

```json
{
  "success": true,
  "data": [
    { "videoId": "7312458901234567", "score": 184.32 },
    { "videoId": "7312458901234512", "score": 96.10 }
  ]
}
```

`score` là **điểm nội bộ, không có đơn vị**. Đừng hiển thị nó, đừng so sánh giữa hai lần gọi cách nhau vài giờ: nó suy giảm theo thời gian nên cùng một video sẽ tụt điểm dù không có gì thay đổi. Chỉ thứ tự trong một response là có nghĩa.

Bảng xếp hạng tính trên **24 giờ gần nhất**, giờ mới nặng hơn giờ cũ rất nhiều. Video hot tháng trước không còn nằm ở đây.

### 3.2 `GET /feed?limit=20` — gợi ý cá nhân hoá

**Bắt buộc token.** Trả về gợi ý riêng cho người đang đăng nhập.

```json
{
  "success": true,
  "data": [
    { "videoId": "7312458901234567", "score": 2.6412, "reasons": ["tag:dance", "trending"] },
    { "videoId": "7312458901234512", "score": 1.5000, "reasons": ["trending"] }
  ]
}
```

- `reasons` là **công cụ debug**, không phải nội dung để hiển thị. Nó cho biết vì sao video lọt vào danh sách: `trending` (đang thịnh hành) hoặc `tag:<tag>` (khớp sở thích). Format có thể đổi.
- `score` cũng chỉ có nghĩa trong một response, như mục 3.1.
- Người dùng mới chưa xem gì sẽ nhận **đúng danh sách trending** — đó là hành vi đúng, không phải lỗi.
- Danh sách **đã loại các video người này đã xem**, dựa trên `POST /interactions/videos/{id}/watch`. Xem mục 5.
- Có thể trả về **mảng rỗng** khi hệ thống chưa có dữ liệu (mới deploy, Redis vừa bị xoá). Client phải xử lý được trạng thái này thay vì hiện màn hình trắng.

Lỗi: `401` (thiếu/hết hạn token).

## 4. Chỉ có id — client phải tự lấy nội dung video

Response ở cả hai endpoint **không có** `title`, `hlsUrl`, `thumbnailUrl`, tên tác giả hay bất cứ thứ gì hiển thị được. Luồng đúng ở client:

1. Gọi `/feed` (hoặc `/trending`) lấy danh sách `videoId` theo thứ tự.
2. Bỏ những id đã có sẵn trong cache local.
3. Lấy phần còn lại từ `video-service` (`docs/video-service-api.md`).
4. **Hiển thị theo đúng thứ tự của bước 1**, không phải theo thứ tự `video-service` trả về.

Lý do không gộp sẵn: `recommendation-service` không được phép đọc dữ liệu của `video-service`, và client thường đã có sẵn phần lớn nội dung nên gộp sẵn chỉ tốn băng thông.

Hệ quả cần xử lý: một id có thể đã bị **xoá hoặc gỡ (takedown)** giữa lúc xếp hạng và lúc client hỏi. `video-service` sẽ trả `404`/không trả video đó — **bỏ qua im lặng**, đừng báo lỗi cho người dùng.

## 5. Feed phụ thuộc vào `/watch` — không gọi thì feed hỏng

Đây là ràng buộc quan trọng nhất của tài liệu này.

`recommendation-service` biết người dùng đã xem gì **chỉ qua** `POST /api/v1/interactions/videos/{videoId}/watch` (mục 3.9 của `docs/interaction-service-api.md`). Nếu client không gửi:

- feed sẽ **trả lại đúng những video vừa xem**, vì không có gì đánh dấu chúng đã xem rồi;
- hồ sơ sở thích theo tag không bao giờ hình thành, nên `/feed` mãi mãi bằng `/trending`.

Bắn `/watch` khi **kết thúc một phiên xem** (người dùng vuốt sang video khác, thoát màn hình, hoặc app vào background) — mỗi phiên một lần, không bắn theo tick.

Tag ảnh hưởng đến sở thích theo **tỉ lệ đã xem**, và xem dưới 20% được tính là **bỏ qua** — nó kéo sở thích với tag đó xuống. Đây là chủ ý: vuốt qua là tín hiệu tiêu cực duy nhất mà feed nhận được.

## 6. Bảng lỗi

| Code | HTTP | Khi nào |
|---|---|---|
| — | 401 | Thiếu `Authorization`, token sai/hết hạn, hoặc dùng refresh token thay access token — chỉ với `/feed` |
| — | 429 | Vượt hạn mức của gateway (20 req/s, burst 40, tính theo IP) |

Không có mã lỗi nghiệp vụ riêng: hai endpoint đều là đọc và đều trả mảng, kể cả mảng rỗng.

## 7. Ghi chú tích hợp

- **Phân trang**: chưa có. `limit` tối đa `100` và không có con trỏ trang. Feed vô hạn ở client nên gọi lại `/feed` khi gần hết danh sách — vì các video đã xem đã bị loại, lần gọi sau sẽ ra nội dung khác.
- **Cache**: đừng cache response quá vài phút. Bảng xếp hạng được dựng lại mỗi phút.
- **Đừng gọi `/feed` cho từng video**. Một lần gọi cho cả trang.
- Hạn mức theo IP dùng chung với mọi endpoint khác qua gateway; nhiều thiết bị sau cùng một NAT chia nhau hạn mức này.
