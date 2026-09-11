# Realtime số liệu & trạng thái video

Ngày: 2026-09-09

## 1. Vấn đề

Frontend `tiktok-cloned` chỉ đọc số liệu video một lần lúc render. Một like, một
comment, một lượt share từ người khác không đến được màn hình đang mở; người dùng
phải reload mới thấy đúng. Cùng lúc đó, chưa có gì chặn một client bấm
like/unlike liên tục, nên mỗi lần bấm là một event Kafka thật — và khi chức năng
notification ra đời, mỗi event đó sẽ là một thông báo gửi cho chủ video.

Spec này mô tả một đường đẩy dữ liệu từ Kafka tới trình duyệt, và ba lớp chống
spam quanh nó.

## 2. Phạm vi

Trong phạm vi:

- Đẩy realtime cho **mọi video đang render trong feed**, không chỉ video đang phát.
- Số liệu: like, comment, share, view, **save**.
- Trạng thái: `VideoStatus` (bao gồm takedown/restore) và `VideoVisibility`.
- Comment mới và comment bị xoá.
- Chống spam like/unlike ở client, ở service, và ở tầng đẩy.
- Thiết kế khoá gộp notification, để entity `Notification` không phải migrate sau.

Ngoài phạm vi: xây dựng notification-service thật; realtime cho chat (đã có);
realtime cho trang admin.

## 3. Kiến trúc

`chat-service` trở thành realtime hub. Nó đã có mọi thứ transport cần: STOMP
endpoint `/ws`, `JwtHandshakeInterceptor`, `UserPrincipalHandshakeHandler`,
SockJS fallback, và gateway đã route `/ws/**` tới nó. Thêm một service mới chỉ để
lặp lại từng ấy thứ là trả giá một module Maven, một Dockerfile, một route
gateway và một bản sao của JWT handshake để đổi lấy một cái tên đúng hơn.

```
interaction.like-events    ─┐
interaction.comment-events ─┤
interaction.share-events   ─┤   VideoStatsFanout        DirtyVideoFlusher
interaction.save-events    ─┼──► (@KafkaListener) ────► (@Scheduled 500ms) ──► /topic/videos.{id}
interaction.view-events    ─┤    đánh dấu dirty          đọc counts, gửi 1 frame
                            │
video.video-events         ─┼──► VideoStateFanout ─────────────────────────► /topic/videos.{id}
admin.moderation-events    ─┘    (gửi ngay, không gộp)

interaction.comment-events ────► CommentFanout ──────────────────► /topic/videos.{id}.comments
```

### 3.1 Fan-out trên nhiều replica

`enableSimpleBroker` giữ subscription trong bộ nhớ của từng instance. Một event
tới instance A không tự đến được client đang cắm vào instance B.

Cách giải quyết là chọn `group.id` cho các listener realtime **duy nhất theo
instance** (`realtime-${random.uuid}` trong `application.yml`): mỗi replica trở
thành một consumer group riêng, nên mọi replica nhận mọi record, và mỗi replica
chỉ bơm cho những client đang cắm vào chính nó. Điều này thay thế một STOMP relay
ngoài (RabbitMQ) bằng một dòng cấu hình.

Hệ quả phải chấp nhận: offset của các group này không có ý nghĩa lâu dài và sẽ
tích tụ trong Kafka. `auto.offset.reset: latest` cho các listener realtime — một
instance vừa khởi động không có việc gì phải phát lại số liệu của hôm qua.

### 3.2 Frame

Kênh `/topic/videos.{videoId}` mang hai loại frame, phân biệt bằng field `type`:

```json
{ "type": "counts", "videoId": "7312...",
  "likeCount": 41, "commentCount": 7, "shareCount": 2,
  "saveCount": 3, "viewCount": 900 }
```

```json
{ "type": "state", "videoId": "7312...",
  "status": "TAKEN_DOWN", "visibility": "PUBLIC" }
```

Kênh `/topic/videos.{videoId}.comments`:

```json
{ "type": "comment.created", "videoId": "...", "commentId": "...",
  "userId": "...", "content": "...", "createdAt": "..." }
```

```json
{ "type": "comment.deleted", "videoId": "...", "commentId": "..." }
```

**Frame `counts` là snapshot tuyệt đối, không phải delta.** Một frame rơi mất thì
lần flush kế tiếp sửa lại; một delta rơi mất thì con số trên màn hình sai vĩnh
viễn cho tới khi reload, đúng cái bệnh spec này sinh ra để chữa. Đây cũng là lý do
`DirtyVideoFlusher` đọc lại counts thay vì cộng dồn từ event.

### 3.3 Gộp 500ms

`VideoStatsFanout` không gửi gì. Nó chỉ `dirtyVideoIds.add(videoId)` trên một
`Set` đồng thời. `DirtyVideoFlusher` chạy `@Scheduled(fixedDelay = 500)`, lấy và
xoá toàn bộ set, chia thành lô 50 id, gọi interaction-service một lần mỗi lô, gửi
một frame cho mỗi video.

Một video đang hot nhận 200 like/giây vẫn chỉ sinh 2 frame/giây cho mỗi người
xem. Một người bấm like/unlike 30 lần trong 3 giây chỉ sinh 6 frame. Gộp ở đây là
lớp chống spam rẻ nhất vì nó bảo vệ mọi nguồn spam cùng lúc, kể cả nguồn chưa
tồn tại.

Chỉ gửi frame cho video **đang có người subscribe**. Không có subscriber thì bỏ
qua luôn cả lời gọi HTTP — đó là phần lớn video trong mọi lô.

### 3.4 Trạng thái video không gộp

`VideoStateFanout` gửi ngay khi nhận event. Các chuyển trạng thái này hiếm (một
lần mỗi video), và một video vừa bị takedown cần biến khỏi màn hình ngay chứ
không phải sau nửa giây.

Định tuyến theo header `eventType`, không đoán từ shape JSON, đúng §Kafka của
CLAUDE.md. Header vắng mặt trên `video.video-events` nghĩa là
`VideoPublishedEvent`. Consumer phải no-op với `videoId` lạ — `VideoDeletedEvent`
có thể mang một videoId chưa từng được announce.

Các event và trạng thái tương ứng:

| Event | Topic | Frame gửi đi |
|---|---|---|
| `VideoPublishedEvent` | `video.video-events` | `status: PUBLISHED` |
| `VideoDeletedEvent` | `video.video-events` | `status: DELETED` |
| `VideoVisibilityChangedEvent` | `video.video-events` | `visibility: <mới>` |
| `VideoTakenDownEvent` | `admin.moderation-events` | `status: TAKEN_DOWN` |
| `VideoRestoredEvent` | `admin.moderation-events` | `status: PUBLISHED` |

`VideoRestoredEvent` không mang trạng thái trước takedown, còn video-service thì
khôi phục về `statusBeforeTakedown`. Frame gửi `PUBLISHED` là một xấp xỉ: client
coi frame `state` là gợi ý để refetch video đó, không phải nguồn sự thật. Điều
này giữ fanout không phải gọi ngược video-service.

## 4. Thay đổi ở interaction-service

### 4.1 `save_count` chưa tồn tại

Không có `save_count` ở bất kỳ đâu trong hệ thống. `video_counters` chỉ có
like/comment/share/view, và `SaveStatusResponse` ghi rõ save là riêng tư của từng
người. Muốn hiển thị số lưu công khai thì phải:

- Thêm cột counter `save_count` vào bảng `video_counters` (Cassandra: `ALTER TABLE
  video_counters ADD save_count counter`).
- `VideoCounters.saveCount` (boxed `Long`, như các counter khác — cột counter độc
  lập nhau về null).
- `VideoCountersRepository.incrementSaveCount`.
- `SaveServiceImpl` tăng/giảm counter với đúng cùng cấu trúc bù trừ mà
  `LikeServiceImpl` đang dùng: LWT là thứ cấp quyền chạm counter, và mọi bước sau
  nó thất bại thì phải trả claim lại theo thứ tự ngược.
- `VideoCounts` và `InteractionCountResponse` thêm `saveCount`.

`SaveStatusResponse` vẫn chỉ trả `saved` — số lưu đi theo đường counts chung.

### 4.2 Save phải phát event

`SaveServiceImpl` hiện không phát Kafka event nào. Realtime cần biết khi nào số
lưu đổi, nên thêm `VideoSavedEvent(videoId, userId, saved)` vào
`libs/event-schema` (cùng shape `VideoLikeEvent`) và
`InteractionEventPublisher.publishSave` trên topic mới
`interaction.save-events`, dùng `confirm()` như like/share vì nó chạm counter.

### 4.3 Endpoint counts theo lô

Đã có `GET /api/v1/interactions/videos/{videoId}/counts` (permitAll). Flusher cần
lô:

```
GET /api/v1/interactions/videos/counts/batch?videoIds=1,2,3
```

Cùng khuôn với `like-status/batch` đang có: `distinct()` trước, `limit(50)`, một
point read qua `CounterCacheService` cho mỗi id. permitAll, vì counts đã là dữ
liệu công khai.

### 4.4 Rate limit like

`InteractionRateLimiter` đã tồn tại và đã được view/watch/share dùng. Thêm bucket
`"like-rate"` gọi từ `LikeServiceImpl.like` và `unlike`, ném
`LikeRateLimitedException` mới (theo khuôn `ShareRateLimitedException`).

60 lượt mỗi giờ trên **một** video bởi **một** người: một người xem thật không bao
giờ chạm tới, một script bấm liên tục thì chạm trong vài giây. Áp cho cả like lẫn
unlike vì chuỗi like→unlike→like mới là chuỗi sinh event, còn like lặp thì LWT đã
tự no-op.

## 5. Frontend `tiktok-cloned`

### 5.1 Kết nối

Một client STOMP duy nhất cho toàn app (`@stomp/stompjs`), dùng lại kết nối chat
nếu đã có. JWT gửi qua handshake giống chat.

Một hook `useVideoRealtime(videoIds)`: subscribe `/topic/videos.{id}` cho mọi id
trong danh sách, unsubscribe cho id đã rời khỏi danh sách. Feed truyền vào các id
đang render. Comment sheet mở thì subscribe thêm `/topic/videos.{id}.comments`,
đóng thì huỷ — kênh comment tốn băng thông và không ai nhìn khi sheet đóng.

Frame `counts` ghi đè state; frame `state` kích hoạt refetch video đó.

Nhớ giới hạn của id Snowflake: chúng là `Long` và `JSON.parse` làm hỏng chúng.
Backend phải serialize id trong frame thành **chuỗi**, như các endpoint REST hiện
tại.

### 5.2 Debounce like

Đây là lớp giết phần lớn spam, và nó nằm ở client vì chỉ client mới biết người
dùng vẫn đang bấm.

- Bấm like: đổi UI ngay (optimistic), đặt trạng thái mong muốn, hẹn 500ms.
- Bấm tiếp trong 500ms: chỉ cập nhật trạng thái mong muốn, hẹn lại.
- Hết 500ms: nếu trạng thái mong muốn **khác** trạng thái server đang giữ thì gửi
  đúng một request (`POST` hoặc `DELETE`); nếu trùng thì không gửi gì.

Bấm 10 lần rồi về đúng trạng thái ban đầu ⇒ 0 request. Rời trang khi còn hẹn ⇒
flush ngay trong cleanup, không bỏ.

Request thất bại (kể cả 429 từ §4.4) ⇒ rollback UI về trạng thái server.

## 6. Notification: khoá gộp

Chưa xây trong spec này, nhưng quyết định ngay vì nó định hình `Notification`
entity và migrate sau thì đắt.

Chống "thông báo lặp đi lặp lại" là **gộp theo khoá**, không phải rate limit.
notification-service upsert theo `(recipientId, targetId, type)` trong cửa sổ 24
giờ:

- Like đầu tiên: tạo document, `actorIds = [A]`, `actorCount = 1`, bắn FCM.
- Like thứ hai từ B: `$addToSet actorIds`, `$inc actorCount`, cập nhật
  `updatedAt`. **Không** bắn FCM. Hiển thị "A và 1 người khác đã thích video của
  bạn".
- `VideoLikeEvent(liked = false)` từ A: `$pull actorIds`, `$inc actorCount: -1`.
  `actorCount` về 0 thì xoá document.

Một người like/unlike/like vẫn cho đúng một notification và đúng một push. FCM chỉ
bắn khi document **được tạo mới**, không bắn khi `$inc` — đó là toàn bộ chỗ phân
biệt giữa thông báo và spam.

`Notification` do đó cần `actorIds: List<Long>`, `actorCount: int`, `targetId`, và
một unique index `(recipientId, targetId, type)`.

## 7. Xử lý lỗi

| Hỏng | Hành vi |
|---|---|
| interaction-service không trả counts | Flusher log và bỏ lô đó. Frame kế tiếp (event tiếp theo trên video đó) sẽ sửa, và client luôn có giá trị từ REST lúc render. |
| Kafka listener throw | `kafka-lib` `DefaultErrorHandler` retry 3 lần rồi DLQ. chat-service phải thêm dependency `kafka-lib`. |
| Client mất kết nối | STOMP tự reconnect; khi reconnect, client refetch counts qua REST cho các video đang render rồi subscribe lại. Không phát lại từ Kafka. |
| Redis chết (rate limit) | Fail-open, đúng như `InteractionRateLimiter` đang làm. |
| Không ai subscribe | Flusher bỏ qua trước khi gọi HTTP. |

Realtime là lớp phủ lên trên REST, không thay thế nó. Mọi màn hình vẫn phải đúng
khi WebSocket không kết nối được.

## 8. Kiểm thử

- `DirtyVideoFlusherTest` — nhiều event trên cùng videoId trong một cửa sổ cho ra
  đúng một frame; video không có subscriber không sinh lời gọi HTTP; lô > 50 id
  chia thành nhiều lời gọi.
- `VideoStateFanoutTest` — định tuyến theo header `eventType`; header vắng mặt cho
  `VideoPublishedEvent`; `videoId` lạ không throw.
- `LikeServiceImplTest` — bổ sung: vượt rate limit ném `LikeRateLimitedException`
  và **không** chạm counter.
- `SaveServiceImplTest` — save/unsave di chuyển `save_count` đúng một lần; thất
  bại sau LWT thì trả claim và counter về nguyên trạng.
- Frontend: test hook debounce — N lần bấm về trạng thái ban đầu ⇒ 0 request;
  N lần bấm về trạng thái ngược lại ⇒ đúng 1 request.

## 9. Thứ tự làm

1. `save_count`: cột Cassandra, entity, repository, `SaveServiceImpl`, `VideoCounts`.
2. `VideoSavedEvent` + `interaction.save-events` + publisher.
3. `counts/batch` endpoint.
4. `like-rate` bucket + exception.
5. chat-service: `kafka-lib`, listener, fanout, flusher, DTO frame.
6. Frontend: STOMP hook, debounce like.

Bước 1–4 độc lập với 5–6 và có thể ship trước.
