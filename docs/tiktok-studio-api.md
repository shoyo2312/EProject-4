# TikTok Studio — API cho frontend

Studio là trang của creator xem số liệu và quản lý nội dung **của chính mình**. Mọi endpoint dưới đây
đều bắt buộc token và luôn trả lời về người gọi: không có tham số `userId`, nên không có cách hỏi số liệu
của người khác. Base URL qua gateway: `http://<host>:8080`. Envelope `ApiResponse` như mọi service khác.

## 1. Màn nào gọi gì

| Màn Studio | Endpoint | Ghi chú |
|---|---|---|
| Trang chủ — 5 thẻ số liệu + biểu đồ | `GET /api/v1/analytics/creator/overview?days=7` | §2.1 |
| Trang chủ — bài đăng gần đây | `GET /api/v1/videos/users/{myId}?size=5` | đã có sẵn, xem `video-service-api.md` |
| Trang chủ — bình luận mới nhất | `GET /api/v1/interactions/users/me/comments?size=5` | `interaction-service-api.md` §3.16 |
| Bài đăng — bảng mọi video | `GET /api/v1/videos/me?status=&visibility=&q=&sort=` | **mọi status** (đang xử lý, chờ duyệt, bị từ chối, bị gỡ) kèm `viewCount`/`likeCount`/`commentCount`; lọc, tìm, sắp xếp — `video-service-api.md` §3.5b |
| Bài đăng — đổi quyền xem, tắt comment, xoá | `PATCH /videos/{id}/visibility`, `PATCH /videos/{id}/comments-setting`, `DELETE /videos/{id}` | đã có sẵn |
| Phân tích › Tổng quan | `GET /api/v1/analytics/creator/overview?days=` | §2.1 |
| Phân tích › Nội dung | `GET /api/v1/analytics/creator/videos/top?days=&sort=` | §2.2, hydrate bằng `GET /api/v1/videos/batch?ids=` |
| Phân tích › Người xem | `GET /api/v1/analytics/creator/viewers?days=` | §2.4 |
| Chi tiết một video | `GET /api/v1/analytics/creator/videos/{videoId}?days=` | §2.3 |
| Bình luận | `GET /api/v1/interactions/users/me/comments?videoId=&status=&q=&from=&to=` | lọc theo video, chờ duyệt, nội dung, khoảng thời gian; duyệt/xoá qua endpoint theo video |
| Tải lên | `POST /api/v1/videos/upload-url` → PUT file → `POST /api/v1/videos` | đã có sẵn |

`days` là 7, 28, 60 hoặc 365 theo bộ chọn của Studio; server nhận mọi giá trị 1–365. Ngoài khoảng → 400.

**Ngày tính theo giờ Việt Nam** (`Asia/Ho_Chi_Minh`): cửa sổ 7 ngày là 7 ngày lịch kết thúc hôm nay, kể cả hôm nay.

## 2. Analytics của creator (`analytics-service`)

### 2.1 `GET /creator/overview?days=7`

```json
{
  "current":  { "views": 120, "likes": 30, "comments": 4, "shares": 2, "netFollowers": 5 },
  "previous": { "views": 80,  "likes": 25, "comments": 6, "shares": 0, "netFollowers": 1 },
  "daily": [
    { "day": "2026-10-17", "views": 10, "likes": 3, "comments": 0, "shares": 0, "followersGained": 1, "followersLost": 0 }
  ]
}
```

- `previous` là cửa sổ cùng độ dài ngay trước đó. Phần trăm thay đổi do client tính; `previous = 0` thì hiện `--` như TikTok.
- `daily` có **đủ mọi ngày** trong cửa sổ, ngày không có gì là số 0.
- `likes`, `comments` là số **ròng**: bỏ thích và comment bị xoá trong cửa sổ được trừ đi, nên có thể âm.
- `views` là số phiên xem (watch session), có thể lệch nhẹ so với `viewCount` trên video.

### 2.2 `GET /creator/videos/top?days=7&sort=VIEWS&limit=10`

`sort` ∈ `VIEWS`, `VIEWERS`, `NEW_VIEWERS`, `LIKES`, `COMMENTS`, `SHARES`; `limit` 1–50. Chỉ gồm video có hoạt động trong cửa sổ.

`newViewers`: người **chưa từng xem** creator, tính cho video đầu tiên họ xem của creator trong cửa sổ — nên cộng mọi video bằng đúng `newViewers` của §2.4. `sort=NEW_VIEWERS` là tab "Nhiều người xem mới nhất" của TikTok.

```json
[ { "videoId": "7312458901234567", "views": 100, "viewers": 50, "newViewers": 8, "likes": 12, "comments": 3, "shares": 1 } ]
```

### 2.3 `GET /creator/videos/{videoId}?days=7`

Tổng là **trọn đời**; `daily` là theo cửa sổ (`followersGained`/`followersLost` luôn 0 ở đây).

```json
{
  "videoId": "7312458901234567",
  "views": 100, "viewers": 60, "totalWatchMs": 540000, "avgWatchMs": 5400, "completionRate": 0.42,
  "likes": 12, "comments": 3, "shares": 1,
  "retention": [1.0, 0.93, 0.88, "... 21 điểm ...", 0.42],
  "daily": [ { "day": "2026-10-17", "views": 10, "likes": 1, "comments": 0, "shares": 0, "followersGained": 0, "followersLost": 0 } ]
}
```

- `retention`: 21 điểm, từ 0% đến 100% độ dài video, bước 5%. Điểm thứ `i` là tỉ lệ lượt xem còn đang xem ở `i×5%`. Video chưa ai xem thì toàn số 0.
- Video không phải của người gọi (hoặc không tồn tại) → 404 `VIDEO_NOT_FOUND`. Cố ý không trả 403, để không xác nhận id đó có tồn tại.

### 2.4 `GET /creator/viewers?days=7`

```json
{
  "totalViewers": 140, "newViewers": 35, "previousTotalViewers": 90, "previousNewViewers": 20,
  "breakdownAvailable": true,
  "activeHours": [0, 0, 1, "... 24 số, từ 00:00 ..."],
  "activeDays":  [12, 9, "... 7 số, từ thứ Hai ..."]
}
```

- Người xem là tài khoản đã đăng nhập (người xem ẩn danh không tính). "Người xem mới" là người xem **lần đầu** bất kỳ video nào của creator rơi vào cửa sổ.
- **Ngưỡng quyền riêng tư:** khi `totalViewers` dưới `analytics.creator.min-audience` (env `CREATOR_MIN_AUDIENCE`, mặc định 100), `breakdownAvailable = false` và hai danh sách là `null`. Với vài người xem, "giờ hoạt động" gần như nói ra *ai* đã xem. Client hiện "Bạn sẽ xem được thông tin này khi có đủ dữ liệu" như TikTok. Demo với ít tài khoản thì hạ env này xuống.

## 3. Nguồn dữ liệu

- Quyền sở hữu video lấy từ dòng `PUBLISHED` mà mọi lần upload ghi vào `engagement_events` (`user_id` = người upload). Không gọi sang video-service.
- Follower ròng đến từ bảng `follow_events` mới, nạp từ topic `user.follow-events`. Follow xảy ra **trước khi deploy** không có trong biểu đồ.
- Hộp bình luận dùng bảng Cassandra `comments_by_owner`, chỉ chứa id. Comment cũ hơn tính năng này không có trong hộp.

## 4. Chưa làm (giai đoạn sau)

Khoảng ngày tuỳ chỉnh (`from`/`to`) cho analytics, nguồn traffic, truy vấn tìm kiếm, lượt xem hồ sơ, follower mới theo từng video, giới tính/tuổi/vị trí,
lên lịch đăng bài, kháng nghị video bị gỡ, hạng mục vi phạm hiển thị cho creator khi admin takedown.
