# Interaction Service — API Contract cho Flutter Mobile

Tài liệu này mô tả **những gì client (Flutter/Dart) cần biết** để tích hợp đúng với `interaction-service`: like, comment, share, view (đếm lượt xem) và watch (báo cáo phiên xem). Không phải tài liệu thiết kế backend — chỉ phần hợp đồng API (contract). Đọc kèm `docs/auth-service-api.md` để lấy `accessToken`, và `docs/video-service-api.md` vì các counter hiển thị trên màn video đến từ **cả hai** service.

Quy tắc token ở đây: **mọi thao tác ghi (`POST`/`DELETE`) đều bắt buộc token**; chỉ ba endpoint đọc là public (`GET /counts`, `GET /comments`, `GET /like-status`). Ở `GET /like-status` thì có token hay không **vẫn đổi kết quả** — xem mục 3.3.

## 1. Tech stack liên quan đến client

| Thành phần | Công nghệ |
|---|---|
| Giao thức | REST/HTTP, JSON |
| Auth | JWT access token, Bearer scheme — bắt buộc với `POST`/`DELETE`, optional với 3 `GET` public |
| Entry point cho mobile | **api-gateway** (`:8080`), KHÔNG gọi thẳng `interaction-service:8085` |
| Base URL (qua gateway) | `http://<gateway-host>:8080/api/v1/interactions` |
| Docs tương tác | `http://localhost:8085/swagger-ui.html` (chạy trực tiếp interaction-service khi dev) |

Lưu trữ phía server là Cassandra (bảng đếm `video_counters` là nguồn sự thật) + Redis cache 5 phút cho các counter. Client không cần biết chi tiết này, trừ một hệ quả duy nhất: **counter luôn đọc được ngay sau khi ghi** trong cùng service, vì mọi thao tác ghi đều xoá cache trước khi trả lời.

## 2. Response envelope

Giống hệt các service khác — mọi response bọc trong `ApiResponse<T>`:

```json
{
  "success": true,
  "data": { },
  "timestamp": "2026-08-20T10:00:00Z"
}
```

Lỗi:

```json
{
  "success": false,
  "code": "COMMENT_NOT_FOUND",
  "message": "Comment not found: 7312458901234567",
  "timestamp": "2026-08-20T10:00:00Z"
}
```

**Không có `PageResponse` ở service này.** Chỉ một endpoint có danh sách (`GET /comments`) và nó phân trang bằng **cursor**, không phải `page` — xem mục 3.5.

> **`videoId` ở đây là số (`Long`), không phải chuỗi như `id` của video-service.** Đây là điểm dễ sai nhất khi ghép hai service: `VideoResponse.id` là `String` (Snowflake 19 chữ số, parse thành `int` sẽ tràn trên Flutter Web), còn mọi path param và mọi field `videoId`/`commentId`/`shareId` trong service này server trả về dạng **số**. Phía Dart: giữ `String` cho id video lấy từ video-service, ghép thẳng vào URL (`/api/v1/interactions/videos/$videoId/like`) — đừng `int.parse` rồi ghép, và khi đọc `videoId` trả về trong response thì đọc thành `int` (Dart native) hoặc `num` rồi `.toString()`, đừng ép kiểu `String` trực tiếp sẽ ném lỗi.

## 3. Endpoints

Base path: `/api/v1/interactions`. Mọi endpoint đều nằm dưới `/videos/{videoId}/...`.

**Không endpoint nào kiểm tra video có tồn tại thật hay không.** interaction-service không giữ dữ liệu video và không gọi sang video-service để hỏi. Like một `videoId` bịa ra vẫn trả `200` và vẫn tạo counter. Hệ quả cho client: **không có `VIDEO_NOT_FOUND` ở service này**, và validate id là việc của luồng đọc video (video-service), không phải của luồng tương tác.

### 3.1 `POST /videos/{videoId}/like` — thích video
**Bắt buộc token.** Trả `200 OK` (không phải `201`).

Không có request body.

Response `data` → `LikeStatusResponse`:
```json
{
  "videoId": 7312458901234567,
  "liked": true,
  "likeCount": 42
}
```

**Idempotent.** Gọi lần thứ hai khi đã like rồi vẫn trả `200` với `liked: true` và `likeCount` **không tăng** — server dùng lightweight transaction `IF NOT EXISTS`, chỉ tăng counter khi hàng thực sự mới. Nên client cứ retry thoải mái khi mất mạng giữa chừng, không sợ đếm đôi.

`liked` trong response **luôn** là `true` (đó là kết quả mong muốn của thao tác, không phải trạng thái trước đó). Muốn biết "vừa rồi có phải like mới không" thì so `likeCount` với số đang giữ trên UI — nhưng thường không cần: đã gọi `/like` thì trạng thái cuối là đã like.

Lỗi: `INTERACTION_CONFLICT` (409), `401`. Xem mục 5 về 409.

### 3.2 `DELETE /videos/{videoId}/like` — bỏ thích
**Bắt buộc token.** Trả `200 OK`, `data` → `LikeStatusResponse` với `liked: false`.

Cũng idempotent theo cùng cơ chế (`IF EXISTS`): bỏ like khi chưa từng like trả `200`, `likeCount` không giảm. Không có lỗi "chưa like".

Lỗi: `INTERACTION_CONFLICT` (409), `401`.

### 3.3 `GET /videos/{videoId}/like-status` — trạng thái like của chính mình
**Không bắt buộc token, nhưng nên gửi nếu đã đăng nhập.** Trả `200 OK`, `data` → `LikeStatusResponse`.

- Có token: `liked` phản ánh đúng người đang đăng nhập.
- Không token (hoặc token hết hạn): `liked` **luôn `false`**, không có lỗi nào báo. `likeCount` vẫn đúng.

Nghĩa là: nếu tim trên UI đột nhiên rỗng sau khi app khởi động lại, hãy kiểm tra token trước khi nghi dữ liệu — giống hệt cái bẫy ở `GET` của video-service.

Endpoint này chỉ cho **một** video. Không có API hỏi trạng thái like của cả một danh sách video, nên khi render feed thì hoặc gọi song song cho từng video đang hiển thị, hoặc chỉ gọi cho video đang phát và cache lại — đừng gọi cho toàn bộ 20 item của trang feed cùng lúc (rate-limit gateway theo IP, mục 6).

### 3.4 `POST /videos/{videoId}/comments` — bình luận
**Bắt buộc token.** Trả `201 Created`.

Request (`AddCommentRequest`):
```json
{
  "content": "Video hay quá",   // bắt buộc, tối đa 1000 ký tự, không được toàn khoảng trắng
  "parentId": 7312458901234999  // tuỳ chọn — id comment đang trả lời (comment gốc HOẶC một reply)
}
```

Response `data` → `CommentResponse`:
```json
{
  "commentId": 7312458901235111,
  "videoId": 7312458901234567,
  "userId": 123456789012345,
  "content": "Đồng ý luôn",
  "createdAt": "2026-08-20T10:00:00Z",
  "parentId": 7312458901234999,  // null nếu là comment gốc; id comment gốc nếu là reply
  "replyToUserId": 123456789012300, // chỉ có khi reply nhắm vào một reply khác — userId tác giả reply đó, để hiện nhãn "A > B". null với comment gốc và reply thẳng vào comment gốc
  "likeCount": 0,                // số like của comment này
  "likedByMe": false            // user gọi đã like chưa — luôn false khi list không kèm token
}
```

Response **không kèm thông tin user** (tên, avatar) — service này chỉ giữ `userId`. Client tự ghép từ user-service hoặc từ dữ liệu người dùng hiện tại đang có sẵn.

**Reply chỉ một cấp** (giống TikTok). Gửi `parentId` là id một comment gốc để trả lời nó. Nếu `parentId` trỏ vào một reply thì server tự dời lên comment gốc của reply đó — cây bình luận luôn phẳng một tầng — và ghi `replyToUserId` = tác giả reply bị nhắm, để client hiện "A > B". `parentId` trỏ vào comment không tồn tại (hoặc đã xoá, hoặc không thuộc `videoId` này) → `COMMENT_NOT_FOUND` (404). Chưa có sửa bình luận. Reply cũng tính vào `commentCount` như một comment thường.

**Không có khử trùng lặp.** Bấm gửi hai lần tạo hai comment khác nhau — khác hẳn like ở mục 3.1. Phía Flutter phải khoá nút gửi cho tới khi có response, và **không** retry tự động khi timeout (nếu request đã tới server thì retry sẽ ra comment đôi).

Lỗi: `VALIDATION_ERROR` (400), `COMMENT_NOT_FOUND` (404 — `parentId` không hợp lệ), `401`.

### 3.5 `GET /videos/{videoId}/comments` — danh sách bình luận
Không cần token. **Phân trang bằng cursor.** Query param: `?cursor=<nextCursor>&size=20`. Trả `200 OK`, `data` → `CommentPageResponse`:

```json
{
  "items": [ /* CommentResponse[] */ ],
  "nextCursor": "AAABBBCCC...",
  "hasMore": true
}
```

Cách dùng:
- Trang đầu: **không gửi** `cursor`.
- Trang tiếp: gửi lại **nguyên văn** `nextCursor` của response trước.
- Dừng khi `hasMore: false` (lúc đó `nextCursor` là `null`).

`nextCursor` là **chuỗi mờ (opaque)** — base64 của paging state Cassandra. Truyền lại y nguyên, không parse, không tự dựng, không lưu lâu dài (nó gắn với câu query của lần gọi đó, đổi `size` giữa chừng thì đừng dùng lại cursor cũ).

> **Một trang có thể trả ít hơn `size` item, thậm chí `items: []`, mà `hasMore` vẫn `true`.** Comment bị xoá được lọc **sau** khi Cassandra đã cắt trang, nên một trang toàn comment đã xoá sẽ rỗng. Đây là hành vi bình thường, **không** phải hết dữ liệu. Client phải lặp theo `hasMore`/`nextCursor`, tuyệt đối đừng dừng khi thấy trang rỗng — và đừng dùng `items.length < size` làm điều kiện dừng.

`size` mặc định 20. **Không có chặn trên ở server** (khác video-service kẹp ở 50), nên đây là chỗ client tự giữ kỷ luật: xin 20–50 là hợp lý, xin vài nghìn thì tự làm app mình chậm.

Thứ tự trả về là thứ tự phân mảnh của Cassandra theo `commentId` trong cùng video, **không cấu hình được** và không nhận tham số `sort`.

**Reply nằm chung danh sách phẳng này**, không có endpoint riêng: mỗi item có `parentId` — `null` là comment gốc, khác `null` là reply thuộc comment gốc đó. Client tự gom `parentId` để dựng cây một tầng. Vì phân trang cắt theo `commentId`, một reply và comment gốc của nó có thể rơi vào hai trang khác nhau; client giữ lại reply nào chưa thấy cha rồi gắn khi trang sau nạp về.

### 3.6 `DELETE /videos/{videoId}/comments/{commentId}` — xoá bình luận của mình
**Bắt buộc token.** Trả `200 OK`, `data` null. Xoá mềm: comment biến mất khỏi mục 3.5 ngay và `commentCount` giảm 1.

Chỉ xoá được comment của **chính mình**. Chủ video **không** xoá được comment của người khác trên video mình (chưa có API kiểm duyệt cho chủ video).

Lỗi: `COMMENT_NOT_FOUND` (404 — id không tồn tại, hoặc đã xoá, hoặc `videoId` không khớp với comment), `NOT_COMMENT_OWNER` (403 — comment tồn tại nhưng của người khác), `401`.

Lưu ý: `videoId` trên URL là **một phần khoá** của comment, không phải trang trí. Truyền sai `videoId` cho một `commentId` có thật vẫn ra `COMMENT_NOT_FOUND` (404) chứ không phải 400.

### 3.6b `POST` / `DELETE /videos/{videoId}/comments/{commentId}/like` — like / bỏ like bình luận
**Bắt buộc token.** Trả `200 OK`, `data` → `CommentLikeResponse`:
```json
{
  "commentId": 7312458901235111,
  "liked": true,      // trạng thái sau lời gọi: POST -> true, DELETE -> false
  "likeCount": 4      // số like mới của comment (đã kẹp >= 0)
}
```

Idempotent: `POST` hai lần chỉ tính một like (giống `/like` của video ở mục 3.1), `DELETE` khi chưa like là no-op. `likeCount` này cũng là `CommentResponse.likeCount` ở mục 3.4/3.5; `likedByMe` ở đó cho biết token hiện tại đã like comment nào.

`videoId` là một phần khoá của comment. `commentId` không tồn tại / đã xoá / không thuộc `videoId` → `COMMENT_NOT_FOUND` (404). Lỗi khác: `401`.

> Đếm like comment lưu **denormalized** trên chính comment, cập nhật không qua LWT — hai người like đúng cùng khoảnh khắc có thể hụt 1. Chấp nhận được, không có gì phụ thuộc con số chính xác tuyệt đối.

### 3.7 `POST /videos/{videoId}/share` — chia sẻ
**Bắt buộc token.** Trả `200 OK`.

Không có request body. Response `data` → `ShareResponse`:
```json
{
  "shareId": 7312458901235111,
  "videoId": 7312458901234567,
  "shareCount": 7
}
```

**Mỗi lần gọi là một lượt chia sẻ mới**, không khử trùng lặp và không giới hạn theo người dùng: cùng một user gọi 5 lần thì `shareCount` tăng 5. Khác like ở chỗ đó. Vì vậy:
- Gọi **sau** khi thao tác chia sẻ thật sự diễn ra (user đã chọn xong app đích trong share sheet), không phải lúc mở share sheet;
- **Không** retry tự động khi timeout — sẽ đếm đôi.

`shareId` trả về dùng để đối soát/log phía client nếu cần; hiện chưa có endpoint nào tra cứu theo `shareId`.

Lỗi: `401`.

### 3.8 `POST /videos/{videoId}/view` — đếm lượt xem
**Bắt buộc token.** Trả `200 OK`. Đây là endpoint làm `viewCount` nhích lên; **server không tự biết ai đang xem**, không gọi thì con số đứng yên mãi mãi.

Request (`ViewRequest`):
```json
{
  "playId": "9f1c0f4e-6b7a-4f3e-9a2b-1d8c5e0a7431"   // bắt buộc, không rỗng, tối đa 64 ký tự
}
```

`playId` là **định danh của một lần phát**, do client tự sinh (UUID) **khi bắt đầu phát** và **vứt đi khi phát xong**. Lần phát sau sinh `playId` mới.

Response `data` → `ViewResponse`:
```json
{
  "videoId": 7312458901234567,
  "counted": true,
  "viewCount": 1043
}
```

- Gọi **một lần khi bắt đầu xem** một video, không gọi theo tick thời gian.
- **Đếm theo từng lần phát.** Cùng một user xem lại video 3 lần = 3 view, giống cách TikTok đếm. Điều kiện duy nhất: mỗi lần phát là một `playId` mới.
- Gửi lại **cùng `playId`** trả `counted: false` và `viewCount` không đổi. Đó là để retry mạng an toàn, không phải lỗi — đừng hiện thông báo gì cho user. Nhớ giữ nguyên `playId` khi retry; sinh mới lúc retry sẽ đếm đôi.
- Giới hạn **60 lần phát / giờ** cho mỗi cặp (người xem, video). Vượt → `VIEW_RATE_LIMITED` (429). Người xem thật không bao giờ chạm ngưỡng này.
- **Xem ẩn danh không được tính.** Không token → `401`, không phải "tính vào lượt xem chung". Không có danh tính thì không có giới hạn nào áp được, và một trình duyệt sẽ thổi phồng được số vô hạn.

Khác `POST /watch` (mục 3.9) như thế nào: `/view` là **con số hiển thị**, bắn lúc bắt đầu phát; `/watch` là **dữ liệu huấn luyện gợi ý**, bắn lúc kết thúc phiên và kèm thời lượng đã phát. Cả hai đều tính mọi lần xem lại. Cần gọi cả hai, ở hai thời điểm khác nhau.

Lỗi: `VIEW_RATE_LIMITED` (429), `401`.

### 3.9 `POST /videos/{videoId}/watch` — báo cáo phiên xem
**Bắt buộc token.** Trả `200 OK`.

Gọi **một lần khi phiên xem kết thúc**: user đóng player, cuộn sang video khác, hoặc rời màn hình. **Không** gọi theo tick tiến độ (mỗi giây / mỗi 25%) — một phiên xem là một dòng dữ liệu, ping theo giây chỉ làm ngập topic bằng các dòng mô tả cùng một phiên.

Request (`WatchRequest`):
```json
{
  "watchedMs": 12500,    // bắt buộc, >= 0. Tổng thời gian ĐÃ PHÁT trong phiên, cộng dồn cả các lần lặp lại
  "durationMs": 15000    // bắt buộc, > 0. Độ dài video theo đúng những gì player thấy
}
```

`watchedMs` là **thời gian phát thật**, không phải vị trí con trỏ và không phải thời gian mở màn hình: tua tới không làm nó tăng, tạm dừng không làm nó tăng, xem lặp lại 3 vòng một video 15 giây thì gửi `45000`. `durationMs` gửi từ player chứ không lấy từ `VideoResponse.durationSeconds` — hai số có thể lệch nhau trong lúc video được transcode lại, và cái server cần là tỉ lệ trên phần thực sự phát được.

Response `data` → `WatchResponse`:
```json
{
  "videoId": 7312458901234567,
  "watchedMs": 12500,
  "completed": true
}
```

- `watchedMs` trong response là số **server đã ghi**, tức số client gửi đã bị **kẹp xuống tối đa bằng `durationMs`**. Gửi `watchedMs: 999999` cho video 15 giây thì nhận lại `15000`. Kẹp ở server nên client không cần tự kẹp, nhưng cũng đừng dựa vào việc gửi số vống lên để "đẩy" video lên gợi ý — nó không có tác dụng.
- `completed` do **server** quyết định (hiện tại: xem >= 90% độ dài), không phải client gửi lên. Ngưỡng này có thể đổi mà không cần client phát hành bản mới, nên đừng hardcode 90% ở phía Flutter để đoán trước kết quả.

**Không khử trùng lặp, không lưu lại thành bản ghi đọc được.** Mỗi lần gọi là một phiên; xem lại lần thứ ba vẫn gửi và vẫn được ghi nhận (xem lại là tín hiệu mạnh nhất cho hệ gợi ý). Hệ quả: **đừng retry tự động** khi timeout — một phiên bị đếm hai lần làm bẩn dữ liệu huấn luyện. Mất một phiên vì mạng hỏng thì bỏ qua, không sao.

Endpoint này **không đổi bất kỳ counter nào** — `viewCount`, `likeCount`... đều không nhích. Nó cũng không có endpoint đọc tương ứng: không có API nào trả về lịch sử xem hay thời lượng đã xem của user.

Lỗi: `VALIDATION_ERROR` (400 — thiếu field, `watchedMs` âm, `durationMs` <= 0), `401`.

### 3.10 `GET /videos/{videoId}/counts` — 4 counter của một video
Không cần token. Trả `200 OK`, `data` → `InteractionCountResponse`:

```json
{
  "videoId": 7312458901234567,
  "likeCount": 42,
  "commentCount": 8,
  "shareCount": 7,
  "viewCount": 1043
}
```

Đây là **nguồn sự thật** của 4 con số này. Số cùng tên trong `VideoResponse` của video-service đến qua Kafka nên trễ hơn — xem mục 4.

`videoId` chưa có tương tác nào trả về **tất cả bằng 0**, không phải 404.

## 4. Counter đến từ hai nơi — đọc số nào, khi nào

| Nguồn | Endpoint | Đặc điểm |
|---|---|---|
| interaction-service | `GET /api/v1/interactions/videos/{id}/counts` | Nguồn sự thật. Cập nhật ngay trong request ghi (like/comment/share/view đều xoá cache trước khi trả lời) |
| video-service | `VideoResponse.likeCount` / `commentCount` / `viewCount` trong `/feed`, `/{videoId}`, `/users/{userId}` | Bản sao, cập nhật **bất đồng bộ qua Kafka**, trễ vài trăm ms tới vài giây. **Không có `shareCount`** |

Chiến lược cho Flutter:

1. **Optimistic update ngay khi bấm** — tăng/giảm số trên UI trước, không chờ response.
2. Response của `/like`, `/share`, `/view` đã kèm counter mới nhất, dùng nó để chỉnh lại con số vừa đoán.
3. Khi render feed thì cứ dùng số có sẵn trong `VideoResponse` (đỡ một request cho mỗi item); chỉ gọi `/counts` cho video đang phát hoặc màn chi tiết nếu cần số chính xác tuyệt đối.
4. **Đừng gọi `GET /videos/{videoId}` của video-service ngay sau khi like** để lấy số mới — sẽ ra số cũ và làm UI nhảy ngược.

Ba con số của video-service có thể **lệch vĩnh viễn** với `/counts` nếu một event Kafka bị mất (service này chưa có outbox). Đây là lệch nhỏ và không tự sửa; khi hai số mâu thuẫn thì `/counts` đúng.

## 5. Bảng mã lỗi (`code`) đầy đủ

| `code` | HTTP status | Khi nào xảy ra |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `content` rỗng/quá 1000 ký tự (mục 3.4); thiếu `watchedMs`/`durationMs`, `watchedMs` âm, `durationMs` <= 0 (mục 3.9) |
| `NOT_COMMENT_OWNER` | 403 | Xoá bình luận của người khác (mục 3.6) |
| `COMMENT_NOT_FOUND` | 404 | Bình luận không tồn tại, đã xoá, hoặc `videoId` trên URL không khớp với bình luận đó |
| `INTERACTION_CONFLICT` | 409 | Ghi vào Cassandra không xác nhận được kết quả sau 3 lần thử (like/unlike ở mục 3.1–3.2, view ở mục 3.8). Xem bên dưới |
| `INTERNAL_ERROR` | 500 | Lỗi không xác định |

Ngoài ra `401 Unauthorized` (không có `code` riêng của interaction-service, đến từ `security-lib`/gateway) xảy ra ở mọi `POST`/`DELETE` khi thiếu/hết hạn/token bị thu hồi — xử lý giống auth-service doc (thử `/refresh` **một lần** rồi mới logout local).

> **`INTERACTION_CONFLICT` (409) không phải lỗi của request.** Nó nghĩa là "server không biết chắc thao tác đã xong hay chưa" — hạ tầng lưu trữ trả về timeout ở một thao tác mà đoán bừa sẽ làm sai counter. **Retry được, và retry là an toàn**: like/unlike idempotent, và `/view` idempotent theo `playId` (mục 3.1, 3.8). Hiện thông báo dạng "thử lại" chứ đừng hiện lỗi kỹ thuật, và đừng đảo ngược optimistic update ngay lập tức — retry một lần trước đã.

**Không có `VIDEO_NOT_FOUND`** ở service này, kể cả với `videoId` bịa hoàn toàn (xem đầu mục 3).

## 6. Rate limiting

- Chỉ có rate-limit **ở tầng gateway** (`:8080`, theo IP — 20 request/giây, burst 40, dùng chung toàn bộ API, tức là chia sẻ hạn mức với auth/user/video).
- **Không có** giới hạn riêng theo tài khoản ở interaction-service: không giới hạn số comment mỗi phút, số like mỗi phút, hay số lần share.
- Hạn mức theo IP là lý do **không** gọi `/like-status` cho cả 20 video của một trang feed cùng lúc (mục 3.3), và **không** gửi `/watch` theo tick (mục 3.9). Nhiều thiết bị sau cùng một NAT dùng chung hạn mức này.

## 7. Những điều KHÔNG nên làm phía Flutter

- Không retry tự động `POST /comments`, `POST /share`, `POST /watch` — ba endpoint này **không** khử trùng lặp, retry sẽ tạo comment đôi / đếm share đôi / bẩn dữ liệu huấn luyện. (Retry `/like`, `/like-status` an toàn; retry `/view` an toàn **nếu giữ nguyên `playId`**.)
- Không gọi `/view` theo tick thời gian, và không gọi `/watch` theo tiến độ phát — `/view` một lần lúc bắt đầu, `/watch` một lần lúc kết thúc.
- Không coi `counted: false` ở mục 3.8 là lỗi — đó là "hôm nay bạn đã được tính rồi".
- Không dừng phân trang comment khi gặp trang rỗng — lặp theo `hasMore`, vì comment đã xoá được lọc sau khi cắt trang (mục 3.5).
- Không parse hay tự dựng `nextCursor` — chuỗi mờ, truyền lại nguyên văn.
- Không ép `videoId` trong response thành `String` bằng cast — server trả về số; ngược lại, id lấy từ video-service phải giữ nguyên `String` (mục 2).
- Không quên gửi token ở `GET /like-status` khi user đã đăng nhập — sẽ luôn ra `liked: false` mà không có lỗi nào báo.
- Không hardcode ngưỡng 90% để tự tính `completed` — server quyết định và ngưỡng có thể đổi (mục 3.9).
- Không dựa vào `viewCount` trong `VideoResponse` để xác nhận `/view` vừa thành công — số đó đến sau qua Kafka (mục 4).
- Không tự kiểm tra "video có tồn tại không" bằng cách gọi endpoint tương tác — chúng chấp nhận mọi `videoId` (mục 3).
- Không gọi thẳng `interaction-service:8085` từ production build — luôn qua gateway `:8080`.
