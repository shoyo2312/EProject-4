# Interaction Service — API Contract cho Flutter Mobile

Tài liệu này mô tả **những gì client (Flutter/Dart) cần biết** để tích hợp đúng với `interaction-service`: like, comment, share, save (yêu thích), repost, view (đếm lượt xem) và watch (báo cáo phiên xem). Không phải tài liệu thiết kế backend — chỉ phần hợp đồng API (contract). Đọc kèm `docs/auth-service-api.md` để lấy `accessToken`, và `docs/video-service-api.md` vì các counter hiển thị trên màn video đến từ **cả hai** service.

Quy tắc token ở đây: **mọi thao tác ghi (`POST`/`DELETE`) đều bắt buộc token**; chỉ năm endpoint đọc là public (`GET /counts`, `GET /counts/batch`, `GET /comments`, `GET /like-status`, `GET /users/{userId}/reposts`). Mọi `GET` khác — kể cả `GET /comments/{id}/replies` — đều bắt buộc token. Ở `GET /like-status` thì có token hay không **vẫn đổi kết quả** — xem mục 3.3.

## 1. Tech stack liên quan đến client

| Thành phần | Công nghệ |
|---|---|
| Giao thức | REST/HTTP, JSON |
| Auth | JWT access token, Bearer scheme — bắt buộc với `POST`/`DELETE`, optional với 5 `GET` public |
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

**Không có `PageResponse` ở service này.** Mọi danh sách (comment, reply, video đã like/lưu/repost) phân trang bằng **cursor**, không phải `page` — xem mục 3.5.

> **`videoId` ở đây là số (`Long`), không phải chuỗi như `id` của video-service.** Đây là điểm dễ sai nhất khi ghép hai service: `VideoResponse.id` là `String` (Snowflake 19 chữ số, parse thành `int` sẽ tràn trên Flutter Web), còn mọi path param và mọi field `videoId`/`commentId`/`shareId` trong service này server trả về dạng **số**. Phía Dart: giữ `String` cho id video lấy từ video-service, ghép thẳng vào URL (`/api/v1/interactions/videos/$videoId/like`) — đừng `int.parse` rồi ghép, và khi đọc `videoId` trả về trong response thì đọc thành `int` (Dart native) hoặc `num` rồi `.toString()`, đừng ép kiểu `String` trực tiếp sẽ ném lỗi.

## 3. Endpoints

Base path: `/api/v1/interactions`. Phần lớn endpoint nằm dưới `/videos/{videoId}/...`; các endpoint theo lô nằm dưới `/videos/.../batch`, còn danh sách theo người dùng nằm dưới `/users/...` (mục 3.11, 3.15). `/admin/**` (ADMIN) và `/internal/**` (chỉ service nội bộ) không dành cho client.

**Mọi thao tác ghi hỏi video-service trước** (`GET /api/v1/videos/{id}` kèm token của người gọi): video không tồn tại, riêng tư, chưa publish, hoặc người gọi và chủ video đang chặn nhau → `404 VIDEO_NOT_FOUND`. video-service không trả lời được (sập, timeout) thì thao tác vẫn đi qua — một sự cố ở đó không được làm tê liệt mọi lượt like.

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
- Không token: `liked` **luôn `false`**, không có lỗi nào báo. `likeCount` vẫn đúng.
- Token hết hạn: gateway trả `401` — refresh một lần rồi gửi lại. Không bị hạ xuống khách, nên tim của người đã đăng nhập không tự rỗng.

Endpoint này chỉ cho **một** video. Render cả trang feed thì dùng bản theo lô ở mục 3.3b — đừng gọi endpoint này cho từng item (rate-limit gateway theo IP, mục 6).

### 3.3b `GET /videos/like-status/batch?ids=1,2,3` — trạng thái like của cả một trang
**Bắt buộc token** (khác 3.3: khách không có gì để hỏi — mọi `liked` đều `false`, dùng `GET /counts/batch` nếu chỉ cần số). Trả `200 OK`, `data` → `LikeStatusResponse[]`, mỗi phần tử như mục 3.3.

- Tối đa **50 id** mỗi lần; id trùng bị gộp trước khi tính, phần vượt 50 bị bỏ qua (không lỗi).
- Không hỏi video-service: id không tồn tại vẫn trả `liked: false`, `likeCount: 0`.

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
  "likedByMe": false,           // user gọi đã like chưa — luôn false khi list không kèm token
  "replyCount": 3               // số reply còn sống, cho nút "Xem 3 phản hồi". Luôn 0 với một reply
}
```

Response **không kèm thông tin user** (tên, avatar) — service này chỉ giữ `userId`. Client tự ghép từ user-service hoặc từ dữ liệu người dùng hiện tại đang có sẵn.

**Reply chỉ một cấp** (giống TikTok). Gửi `parentId` là id một comment gốc để trả lời nó. Nếu `parentId` trỏ vào một reply thì server tự dời lên comment gốc của reply đó — cây bình luận luôn phẳng một tầng — và ghi `replyToUserId` = tác giả reply bị nhắm, để client hiện "A > B". `parentId` trỏ vào comment không tồn tại (hoặc đã xoá, hoặc không thuộc `videoId` này) → `COMMENT_NOT_FOUND` (404). Chưa có sửa bình luận. Reply cũng tính vào `commentCount` như một comment thường.

**Không có khử trùng lặp.** Bấm gửi hai lần tạo hai comment khác nhau — khác hẳn like ở mục 3.1. Phía Flutter phải khoá nút gửi cho tới khi có response, và **không** retry tự động khi timeout (nếu request đã tới server thì retry sẽ ra comment đôi).

**Hai mã 403 khác nhau trên cùng một nút gửi, đừng gộp copy:**

- `COMMENTS_DISABLED` — chủ video **tắt comment cho video này**. Hiện "Comments are turned off for this video". Chủ video cũng không comment được trên video đã tắt.
- `COMMENTS_NOT_ALLOWED` — cài đặt **tài khoản** của chủ video không nhận comment từ người gọi: `commentAudience = NO_ONE`, hoặc `FRIENDS` mà hai bên không follow lẫn nhau (xem `user-service-api.md` mục 3.15). Hiện "This account doesn't accept comments from you". Dùng chung copy với `COMMENTS_DISABLED` sẽ nói với người xem friends-only rằng một thread họ đang đọc bị khoá với tất cả mọi người.

Cờ từng video và cài đặt tài khoản **đều là hạn chế**, không phía nào override phía nào: video đã tắt comment thì vẫn tắt dù cài đặt tài khoản có thoáng đến đâu, và tài khoản đặt `NO_ONE` thì khoá cả những video chưa từng chạm vào cờ đó. `NO_ONE` chặn **cả chủ video**: tài khoản đặt `NO_ONE` thì mọi video của họ coi như đã tắt comment, y như cờ từng video (video-service trả `accountCommentsOff: true` và `commentCount: null`). Chủ video chỉ được miễn luật `FRIENDS`.

Nếu video-service không trả lời được thì comment **vẫn đi qua** (fail-open) — một sự cố ở video-service không được phép chặn toàn bộ comment của hệ thống. Riêng nhánh `FRIENDS` thì fail-closed: không xác nhận được quan hệ follow là từ chối, vì "chỉ bạn bè" không được phép biến thành "tất cả mọi người" lúc user-service chết.

Lỗi: `VALIDATION_ERROR` (400), `COMMENTS_DISABLED` (403), `COMMENTS_NOT_ALLOWED` (403), `COMMENT_NOT_FOUND` (404 — `parentId` không hợp lệ), `401`.

### 3.4a `GET /videos/{videoId}/comment-permission` — người gọi có được bình luận không
**Bắt buộc token.** Trả `200 OK`, `data` → `CommentPermissionResponse`:

```json
{ "restriction": null }   // null = được bình luận
```

`restriction` đọc **đúng luật** mà mục 3.4 dùng để từ chối, nên ô nhập mà client ẩn đi và mã 403 mà người xem sẽ gặp không bao giờ lệch nhau:

| `restriction` | Nghĩa | Tương ứng lỗi ở 3.4 | Gợi ý hiển thị |
|---|---|---|---|
| `null` | Được bình luận | — | Hiện ô nhập như thường |
| `COMMENTS_DISABLED` | Chủ video tắt comment cho **video này** (ràng cả chủ video) | `COMMENTS_DISABLED` | "This creator has turned off commenting" |
| `NO_ONE` | Tài khoản chủ video không nhận comment từ ai | `COMMENTS_NOT_ALLOWED` | Như tắt comment — với người xem, kết quả giống hệt |
| `FRIENDS_ONLY` | Chỉ bạn bè (follow lẫn nhau), người gọi không phải | `COMMENTS_NOT_ALLOWED` | "You cannot comment on this video" |

Chủ video được miễn `FRIENDS_ONLY` nhưng vẫn nhận `COMMENTS_DISABLED` (tắt comment video đó) và `NO_ONE` (tài khoản không nhận comment từ ai). Hai trường hợp đó client đã biết sẵn từ `commentsDisabled` / `accountCommentsOff` trên video, nên thực tế chỉ cần gọi endpoint này cho `FRIENDS_ONLY`. Fail-open/fail-closed y như 3.4: video-service không trả lời được → `null`; không xác nhận được quan hệ bạn bè → `FRIENDS_ONLY`.

Đây chỉ là gợi ý cho UI — server **vẫn** kiểm tra lại ở 3.4, và audience được cache vài giây (~3s), nên client vẫn phải xử lý hai mã 403 kia.

Lỗi: `VIDEO_NOT_FOUND` (404 — video không tồn tại hoặc người gọi không được xem), `401`.

### 3.5 `GET /videos/{videoId}/comments` — danh sách bình luận gốc
Không cần token. **Chỉ trả comment gốc** (`parentId: null`), **mới nhất trước**; reply lấy riêng qua mục 3.5b. **Phân trang bằng cursor.** Query param: `?cursor=<nextCursor>&size=20`. Trả `200 OK`, `data` → `CommentPageResponse`:

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

> **Một trang có thể trả ít hơn `size` item, thậm chí `items: []`, mà `hasMore` vẫn `true`.** Comment bị xoá được lọc **sau** khi Cassandra đã cắt trang. Server tự đọc tiếp tối đa 5 trang khi gặp trang rỗng, nhưng một chuỗi dài comment đã xoá vẫn có thể ra trang rỗng. Đây là hành vi bình thường, **không** phải hết dữ liệu. Client phải lặp theo `hasMore`/`nextCursor`, tuyệt đối đừng dừng khi thấy trang rỗng — và đừng dùng `items.length < size` làm điều kiện dừng.

`size` mặc định 20, bị kẹp vào khoảng **1–50**. Không nhận tham số `sort`. Cursor hỏng/không giải mã được → `INVALID_COMMENT_CURSOR` (400).

Video đã tắt comment (hoặc tài khoản chủ đặt `NO_ONE`) → trang rỗng, `hasMore: false`, không lỗi.

### 3.5b `GET /videos/{videoId}/comments/{commentId}/replies` — reply của một comment
**Bắt buộc token.** Cùng cách phân trang và cùng response `CommentPageResponse` như 3.5, nhưng thứ tự **cũ nhất trước** (đọc như một cuộc hội thoại) và `size` mặc định **3** (đủ cho lần bấm "Xem phản hồi" đầu tiên; tối đa 50).

- `commentId` là id comment gốc. Comment không tồn tại hoặc không có reply → trang rỗng, **không** 404.
- Video tắt comment → trang rỗng như 3.5. Video không xem được → `VIDEO_NOT_FOUND` (404).
- Dùng `replyCount` ở mục 3.4 để biết có nên hiện nút hay không, thay vì gọi thử.

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
  "durationMs": 15000    // bắt buộc, > 0. Độ dài video theo player — chỉ là tham khảo, xem bên dưới
}
```

`watchedMs` là **thời gian phát thật**, không phải vị trí con trỏ và không phải thời gian mở màn hình: tua tới không làm nó tăng, tạm dừng không làm nó tăng, xem lặp lại 3 vòng một video 15 giây thì gửi `45000`.

`durationMs` client gửi lên **chỉ là phương án dự phòng**. Mẫu số của tỉ lệ xem lấy từ độ dài **server tự probe được lúc transcode** (`durationSeconds` của video-service); chỉ khi chưa có số đó — video còn đang transcode, hoặc video-service không gọi tới được — server mới dùng số của client, và khi đó kẹp vào khoảng **[3 giây, 10 phút]**. Nghĩa là gửi `{"watchedMs": 1, "durationMs": 1}` **không** còn tạo ra một phiên hoàn thành 100%: mẫu số bị nâng lên sàn 3 giây nên tỉ lệ gần như 0. Đừng cố khai số để đẩy video lên gợi ý — nó không có tác dụng.

Response `data` → `WatchResponse`:
```json
{
  "videoId": 7312458901234567,
  "watchedMs": 12500,
  "completed": true
}
```

- `watchedMs` trong response là số **server đã ghi**, tức số client gửi đã bị **kẹp xuống tối đa bằng độ dài server dùng làm mẫu số** (probe được thì là số đó, không thì là `durationMs` đã kẹp). Gửi `watchedMs: 999999` cho video 15 giây thì nhận lại `15000`. Kẹp ở server nên client không cần tự kẹp, nhưng cũng đừng dựa vào việc gửi số vống lên để "đẩy" video lên gợi ý — nó không có tác dụng.
- `completed` do **server** quyết định (hiện tại: xem >= 90% độ dài), không phải client gửi lên. Ngưỡng này có thể đổi mà không cần client phát hành bản mới, nên đừng hardcode 90% ở phía Flutter để đoán trước kết quả.

**Không khử trùng lặp, không lưu lại thành bản ghi đọc được.** Mỗi lần gọi là một phiên; xem lại lần thứ ba vẫn gửi và vẫn được ghi nhận (xem lại là tín hiệu mạnh nhất cho hệ gợi ý). Hệ quả: **đừng retry tự động** khi timeout — một phiên bị đếm hai lần làm bẩn dữ liệu huấn luyện. Mất một phiên vì mạng hỏng thì bỏ qua, không sao.

Endpoint này **không đổi bất kỳ counter nào** — `viewCount`, `likeCount`... đều không nhích. Nó cũng không có endpoint đọc tương ứng: không có API nào trả về lịch sử xem hay thời lượng đã xem của user.

Lỗi: `VALIDATION_ERROR` (400 — thiếu field, `watchedMs` âm, `durationMs` <= 0), `401`.

### 3.10 `GET /videos/{videoId}/counts` — 6 counter của một video
Không cần token. Trả `200 OK`, `data` → `InteractionCountResponse`:

```json
{
  "videoId": 7312458901234567,
  "likeCount": 42,
  "commentCount": 8,
  "shareCount": 7,
  "viewCount": 1043,
  "saveCount": 5,
  "repostCount": 2
}
```

Đây là **nguồn sự thật** của các con số này. Số cùng tên trong `VideoResponse` của video-service đến qua Kafka nên trễ hơn — xem mục 4.

`videoId` chưa có tương tác nào trả về **tất cả bằng 0**, không phải 404.

### 3.10b `GET /videos/counts/batch?videoIds=1,2,3` — counter của cả một trang
Không cần token. Trả `data` → `InteractionCountResponse[]` theo thứ tự id gửi lên (đã bỏ trùng). Tối đa **50 id**, phần vượt bị bỏ qua. Lưu ý tên param là `videoIds`, khác `ids` của 3.3b/3.14.

### 3.11 `GET /users/{userId}/reposts` — tab Reposts của một profile
Nằm ngoài `/videos/...`. Không bắt buộc token, **nhưng gửi nếu đã đăng nhập**. Query param: `?cursor=<nextCursor>&size=20` (tối đa 50). Trả `200 OK`, `data` → `VideoIdPageResponse`:

```json
{
  "videoIds": [7312458901234567],
  "nextCursor": "…",
  "hasMore": true
}
```

- Tài khoản công khai: ai cũng xem được, giống chính chủ.
- Tài khoản **riêng tư**: chỉ chủ và follower. Người khác (kể cả không token) → `PRIVATE_ACCOUNT` (403). Token là cách server nhận ra follower, nên thiếu token là bị coi như người lạ.
- Mỗi lần mở tab của người khác, service hỏi user-service. user-service không trả lời được thì tab vẫn không mở (fail-closed), nhưng lỗi là `PRIVACY_CHECK_UNAVAILABLE` (503) chứ không phải `PRIVATE_ACCOUNT`: đây là lỗi tạm thời, thử lại được, và không có nghĩa tài khoản riêng tư — đừng hiện "tài khoản riêng tư". Tab của chính chủ không hỏi user-service nên không bị ảnh hưởng.

### 3.12 `POST` / `DELETE /videos/{videoId}/save` — lưu / bỏ lưu (yêu thích)
**Bắt buộc token.** Trả `200 OK`, `data` → `SaveStatusResponse`:

```json
{ "videoId": 7312458901234567, "saved": true }
```

Idempotent như like (LWT): lưu hai lần chỉ tính một, bỏ lưu khi chưa lưu là no-op. Không kèm số đếm — danh sách lưu là riêng tư; `saveCount` công khai lấy ở mục 3.10. Lưu cần video xem được (`VIDEO_NOT_FOUND` 404 nếu không).

`GET /videos/{videoId}/save-status` (**bắt buộc token**) trả cùng shape, để vẽ icon khi mở một video.

Lỗi: `VIDEO_NOT_FOUND` (404), `SAVE_RATE_LIMITED` (429), `INTERACTION_CONFLICT` (409), `INTERACTION_NOT_RECORDED` (503), `401`.

### 3.13 `POST` / `DELETE /videos/{videoId}/repost` — repost / bỏ repost
**Bắt buộc token.** Trả `200 OK`, `data` → `RepostStatusResponse`:

```json
{ "videoId": 7312458901234567, "reposted": true, "repostCount": 2 }
```

Idempotent như like. Video được repost hiện ở tab Reposts của người repost (mục 3.11).

Lỗi: `VIDEO_NOT_FOUND` (404), `REPOST_RATE_LIMITED` (429), `INTERACTION_CONFLICT` (409), `INTERACTION_NOT_RECORDED` (503), `401`.

### 3.14 `GET /videos/repost-context/batch?ids=1,2,3` — badge repost của một trang feed
**Bắt buộc token.** Trả `data` → `RepostContextResponse[]`:

```json
{ "videoId": 7312458901234567, "repostedByMe": false, "reposterIds": [123, 456] }
```

`reposterIds` là **mẫu tối đa 20 người** đã repost, không theo thứ tự nào. Service này không đọc được đồ thị follow, nên client tự giao `reposterIds` với danh sách mình đang follow để hiện "X đã repost". `repostedByMe` đúng kể cả khi người gọi nằm ngoài mẫu. Tối đa 50 id mỗi lần.

### 3.15 `GET /users/me/likes`, `GET /users/me/saves` — video mình đã like / đã lưu
**Bắt buộc token.** `?cursor=<nextCursor>&size=20` (1–50). Trả `data` → `VideoIdPageResponse` (cùng shape mục 3.11), **mới nhất trước**. Chỉ có id — đổi sang video qua `GET /api/v1/videos/batch` của video-service. Chỉ xem được của chính mình; không có endpoint xem like/lưu của người khác. Cursor hỏng → `INVALID_CURSOR` (400).

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
| `INVALID_COMMENT_CURSOR` | 400 | `cursor` của danh sách comment/reply không giải mã được (mục 3.5, 3.5b) |
| `INVALID_CURSOR` | 400 | `cursor` của danh sách video đã like/lưu/repost không giải mã được (mục 3.11, 3.15) |
| `NOT_COMMENT_OWNER` | 403 | Xoá bình luận của người khác (mục 3.6) |
| `COMMENTS_DISABLED` | 403 | Chủ video đã tắt comment cho **video đó** (mục 3.4). Listing (mục 3.5) không lỗi mà trả trang rỗng — tài khoản đặt `NO_ONE` cũng trả trang rỗng như vậy |
| `COMMENTS_NOT_ALLOWED` | 403 | Cài đặt **tài khoản** của chủ video không nhận comment từ người gọi — `NO_ONE`, hoặc `FRIENDS` mà không follow lẫn nhau (mục 3.4) |
| `PRIVATE_ACCOUNT` | 403 | Tab Reposts (mục 3.11) của tài khoản riêng tư mà người gọi không phải chủ hay follower |
| `COMMENT_NOT_FOUND` | 404 | Bình luận không tồn tại, đã xoá, hoặc `videoId` trên URL không khớp với bình luận đó |
| `LIKE_RATE_LIMITED`, `COMMENT_RATE_LIMITED`, `COMMENT_LIKE_RATE_LIMITED`, `SAVE_RATE_LIMITED`, `REPOST_RATE_LIMITED`, `SHARE_RATE_LIMITED`, `VIEW_RATE_LIMITED`, `WATCH_RATE_LIMITED` | 429 | Vượt 60 lần/giờ cho cùng một (người dùng, video) — hoặc (người dùng, comment) với like comment. Xem mục 6 |
| `INTERACTION_CONFLICT` | 409 | Ghi vào Cassandra không xác nhận được kết quả sau 3 lần thử (like/unlike ở mục 3.1–3.2, view ở mục 3.8). Xem bên dưới |
| `PRIVACY_CHECK_UNAVAILABLE` | 503 | Tab Reposts (mục 3.11) của người khác khi không hỏi được user-service tài khoản có riêng tư không. Thử lại; **không** có nghĩa tài khoản riêng tư |
| `INTERACTION_NOT_RECORDED` | 503 | Kafka không nhận event đi kèm thao tác nên thao tác đã được **hoàn tác** — like/unlike, repost, save, comment (tạo/xoá), share, view. Xem bên dưới |
| `INTERNAL_ERROR` | 500 | Lỗi không xác định |

Ngoài ra `401 Unauthorized` (không có `code` riêng của interaction-service, đến từ `security-lib`/gateway) xảy ra ở mọi `POST`/`DELETE` khi thiếu/hết hạn/token bị thu hồi — xử lý giống auth-service doc (thử `/refresh` **một lần** rồi mới logout local).

> **`INTERACTION_NOT_RECORDED` (503) nghĩa là không có gì được lưu.** Khác 409 ở chỗ server biết chắc kết quả: thao tác đã bị hoàn tác, counter không đổi. Retry là an toàn; nếu client đã optimistic update thì đảo lại sau khi retry cũng thất bại.

> **`INTERACTION_CONFLICT` (409) không phải lỗi của request.** Nó nghĩa là "server không biết chắc thao tác đã xong hay chưa" — hạ tầng lưu trữ trả về timeout ở một thao tác mà đoán bừa sẽ làm sai counter. **Retry được, và retry là an toàn**: like/unlike idempotent, và `/view` idempotent theo `playId` (mục 3.1, 3.8). Hiện thông báo dạng "thử lại" chứ đừng hiện lỗi kỹ thuật, và đừng đảo ngược optimistic update ngay lập tức — retry một lần trước đã.

`VIDEO_NOT_FOUND` (404): video-service không cho người gọi xem video đó — không tồn tại, riêng tư, chưa publish, hoặc người gọi và chủ video đang chặn nhau. Mọi thao tác ghi (like, comment, save, repost, share, view) đều hỏi video-service trước; nếu video-service không trả lời được thì thao tác vẫn đi qua (fail-open).

## 6. Rate limiting

- Chỉ có rate-limit **ở tầng gateway** (`:8080`, theo IP — 20 request/giây, burst 40, dùng chung toàn bộ API, tức là chia sẻ hạn mức với auth/user/video).
- Ở interaction-service: mỗi thao tác ghi có hạn mức **60 lần/giờ cho mỗi cặp (người dùng, video)** — like comment thì theo (người dùng, comment). Mỗi loại thao tác một bucket riêng (like và unlike chung bucket `like`, save/unsave chung bucket `save`…), vượt thì trả mã `*_RATE_LIMITED` (429) tương ứng (mục 5). Người dùng thật không chạm ngưỡng này; chạm nghĩa là client đang lặp request. Cửa sổ cố định theo giờ. Redis chết thì bỏ qua giới hạn (fail-open).
- Hạn mức theo IP là lý do **không** gọi `/like-status` cho cả 20 video của một trang feed cùng lúc (mục 3.3), và **không** gửi `/watch` theo tick (mục 3.9). Nhiều thiết bị sau cùng một NAT dùng chung hạn mức này.

## 7. Sự kiện Kafka (Events)

Phần này cho người tích hợp backend. Bảng đầy đủ toàn hệ thống: `docs/ARCHITECTURE.md` §5c.

### Tiêu thụ (consumer) — `AdminModerationEventConsumer`

Một consumer duy nhất (dùng `kafka-lib`): `admin.moderation-events`, route theo header `eventType`, chỉ xử lý `CommentRemovedEvent` → `CommentService.removeByAdmin()`; event khác trên topic bị bỏ qua. Không có bảng inbox: thao tác ghi duy nhất là LWT `markDeletedIfNotDeleted`, redelivery thấy comment đã xoá thì không giảm counter lần hai. `targetId` dạng `"videoId:commentId"`.

### Phát (producer) — `InteractionEventPublisher`

Không có outbox (Cassandra không có transaction đa bảng để ghép). Thay vào đó **publish inline ngay sau khi ghi Cassandra thành công, và chờ broker ack**. Ack fail → ghi Cassandra được bù trừ (compensate) → client nhận `INTERACTION_NOT_RECORDED` (503), thay vì để 3 service lệch counter vĩnh viễn. Mọi topic key = `videoId` (một video giữ thứ tự trong partition).

"Fail" phải là phán quyết cuối của producer, không phải request hết kiên nhẫn trước: producer tự retry một record cho tới hết `delivery.timeout.ms` (mặc định 2 phút), nên request ngừng chờ sớm hơn thì sẽ hoàn tác một thao tác mà broker vài giây sau vẫn nhận — video-service vẫn đếm, notification vẫn gửi. Vì vậy `application.yml` giới hạn producer (`delivery.timeout.ms` 5s, `request.timeout.ms` 4s, `max.block.ms` 2s) và publisher chờ đúng câu trả lời đó (`ACK_BACKSTOP` 10s chỉ là chốt chặn). `InteractionEventPublisherTest` kiểm tra hai con số này không lệch nhau. Còn một khe hở như mọi producer không dùng transaction: request broker đã ghi nhưng response bị mất vẫn bị báo là fail.

| Topic | Event | Payload | Phát khi | Xác nhận ack? |
|---|---|---|---|---|
| `interaction.like-events` | `VideoLikeEvent` | `eventId, occurredAt, videoId, userId, liked` | `POST`/`DELETE /like` (chỉ khi hàng LWT thực sự đổi) | có |
| `interaction.comment-events` | `CommentCreatedEvent` (header `eventType=CommentCreatedEvent`) | `eventId, occurredAt, commentId, videoId, userId, content` | `POST /comments` | có |
| `interaction.comment-events` | `CommentDeletedEvent` (header `eventType=CommentDeletedEvent`) | `eventId, occurredAt, commentId, videoId, userId` | `DELETE /comments/{id}` | có |
| `interaction.share-events` | `VideoSharedEvent` | `eventId, occurredAt, shareId, videoId, userId` | `POST /share` (mỗi lần một event) | có |
| `interaction.comment-like-events` | `CommentLikeChangedEvent` | `eventId, occurredAt, commentId, videoId, userId, liked, likeCount, commentAuthorId` | `POST`/`DELETE /comments/{id}/like` | **không** (số chuẩn đã nằm trong Cassandra và trả về trong response) |
| `interaction.save-events` | `VideoSavedEvent` | `eventId, occurredAt, videoId, userId, saved` | `POST`/`DELETE /save` (chỉ khi hàng LWT thực sự đổi) | có |
| `interaction.repost-events` | `VideoRepostedEvent` | `eventId, occurredAt, videoId, userId, reposted` | `POST`/`DELETE /repost` (chỉ khi hàng LWT thực sự đổi) | có |
| `interaction.view-events` | `VideoViewedEvent` | `eventId, occurredAt, videoId, userId` | `POST /view` — **chỉ** view sống sót qua dedupe theo `playId` | có |
| `interaction.watch-events` | `VideoWatchEvent` | `eventId, occurredAt, videoId, userId, watchedMs, durationMs, completed` | `POST /watch` — **mọi** phiên, kể cả xem lại | **không** (fire-and-forget, chỉ log khi lỗi) |

Vì sao `watch-events` không chờ ack: không counter nào phụ thuộc nó (chỉ là training row + tag affinity), một phiên mất trong hàng trăm phiên/ngày không ảnh hưởng, còn chặn request để chờ ack thì đặt stream dày nhất service này sinh ra lên critical path của mỗi lần cuộn.

### Ai tiêu thụ event của interaction-service

| Topic | Consumer | Dùng để |
|---|---|---|
| `interaction.like-events` | video-service, recommendation-service, search-service, notification-service, chat-service, analytics-service | `likeCount`; điểm trending; `likeCount` trong search index; thông báo; đẩy số realtime; thống kê |
| `interaction.comment-events` | video-service, recommendation-service, search-service, notification-service, chat-service, analytics-service | `commentCount`; điểm trending (giảm khi comment bị xoá); `commentCount` search; thông báo; realtime; thống kê |
| `interaction.comment-like-events` | notification-service, chat-service | thông báo cho tác giả comment; đẩy số like comment realtime |
| `interaction.share-events` | recommendation-service, search-service, notification-service, chat-service, analytics-service | điểm trending; `shareCount` search; thông báo; realtime; thống kê |
| `interaction.save-events` | chat-service | đẩy số realtime |
| `interaction.repost-events` | — | chưa có consumer |
| `interaction.view-events` | video-service, search-service, chat-service | `viewCount`; `viewCount` search; realtime |
| `interaction.watch-events` | recommendation-service, analytics-service | tag affinity + quality score; sink training data cho `rank-service` |

## 8. Những điều KHÔNG nên làm phía Flutter

- Không retry tự động `POST /comments`, `POST /share`, `POST /watch` — ba endpoint này **không** khử trùng lặp, retry sẽ tạo comment đôi / đếm share đôi / bẩn dữ liệu huấn luyện. (Retry `/like`, `/like-status` an toàn; retry `/view` an toàn **nếu giữ nguyên `playId`**.)
- Không gọi `/view` theo tick thời gian, và không gọi `/watch` theo tiến độ phát — `/view` một lần lúc bắt đầu, `/watch` một lần lúc kết thúc.
- Không coi `counted: false` ở mục 3.8 là lỗi — đó là "hôm nay bạn đã được tính rồi".
- Không dừng phân trang comment khi gặp trang rỗng — lặp theo `hasMore`, vì comment đã xoá được lọc sau khi cắt trang (mục 3.5).
- Không gọi `/like-status` hay `/counts` từng video cho cả trang feed — dùng `/like-status/batch`, `/counts/batch`, `/repost-context/batch` (mục 3.3b, 3.10b, 3.14).
- Không parse hay tự dựng `nextCursor` — chuỗi mờ, truyền lại nguyên văn.
- Không ép `videoId` trong response thành `String` bằng cast — server trả về số; ngược lại, id lấy từ video-service phải giữ nguyên `String` (mục 2).
- Không quên gửi token ở `GET /like-status` khi user đã đăng nhập — sẽ luôn ra `liked: false` mà không có lỗi nào báo.
- Không hardcode ngưỡng 90% để tự tính `completed` — server quyết định và ngưỡng có thể đổi (mục 3.9).
- Không dựa vào `viewCount` trong `VideoResponse` để xác nhận `/view` vừa thành công — số đó đến sau qua Kafka (mục 4).
- Không tự kiểm tra "video có tồn tại không" bằng cách gọi endpoint tương tác — thao tác ghi trả `VIDEO_NOT_FOUND` nhưng **cho qua** khi video-service không trả lời (fail-open), còn các endpoint đọc theo lô chấp nhận mọi id (mục 3).
- Không gọi thẳng `interaction-service:8085` từ production build — luôn qua gateway `:8080`.
