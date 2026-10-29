# Chat: Đã gửi / Đã nhận / Đã xem và "đang nhập"

chat-service. Hợp đồng REST/STOMP, quy tắc kiểm tra và những điều cố ý **không** làm.

## 1. Định nghĩa

| Trạng thái | Nghĩa | Bằng chứng |
|---|---|---|
| Đã gửi (`SENT`) | Backend đã lưu tin | Có tin trong DB. Socket của người nhận đang mở hay `convertAndSend` thành công **không** đủ để coi là đã nhận |
| Đã nhận (`DELIVERED`) | Ít nhất một thiết bị của người nhận đã nhận dữ liệu tin và ACK về server | `deliveredMark` của người nhận phủ tới tin đó. Chưa cần mở hội thoại, không làm mất unread |
| Đã xem (`SEEN`) | Người nhận đã hiển thị tin trong hội thoại đang được nhìn (tab hiển thị, cửa sổ có focus, tin nằm trong vùng nhìn) | `readMark` phủ tới tin đó. Đã xem kéo theo đã nhận |

Mở app, tải inbox, nhận notification, tải lịch sử hay chỉ kết nối socket **không** phải là đã xem.

## 2. Mốc (mark) theo vị trí, không theo giờ

`Conversation.deliveredMark` / `readMark`: map `userId -> ReceiptMark(messageId, sentAt, at)`.

* `(sentAt, messageId)` là **vị trí** của tin mới nhất của phía kia mà người này đã nhận/xem. Thứ tự tin là `(sentAt, id)`, nên hai tin cùng mili-giây vẫn phân biệt được.
* `at` là giờ **server** ghi nhận. Chỉ dùng để hiện "Đã xem 13 phút trước". Không bao giờ dùng `at` để suy ra tin nào được phủ: ACK của m1 đến sau khi m2 đã lưu **không** làm m2 thành đã nhận/đã xem.
* Mốc chỉ tiến. `ConversationRepository.advanceMark` là một lệnh cập nhật có điều kiện (chưa có mốc, hoặc vị trí mới hơn), nên ACK trùng, phản hồi cũ, hai request đồng thời không lùi mốc và không ghi đè `at` của mốc không đổi.
* `lastReadAt` (bookkeeping "chưa đọc") chỉ dời tới `sentAt` của tin được đọc, không phải `now`. `lastSentAt` ghi lần gửi cuối của từng người; "chưa đọc" đo theo `lastSentAt` của **phía kia**. Gửi tin không đánh dấu những tin trước đó là đã đọc. Hội thoại cũ không có `lastSentAt` dùng cách tính cũ.
* Dữ liệu cũ (`lastReadAt` Instant) **không** được đổi thành receipt: thiếu căn cứ thì hiện "Đã gửi".

## 3. API

Tất cả dưới `/api/v1/conversations/{id}/messages`, user lấy từ JWT (không nhận `userId` từ client).

* `POST /delivered` body `{ "messageIds": [..<=100] }` -> `ReceiptView` của **chính người gọi**.
  * Mọi id phải thuộc hội thoại, do **phía kia** gửi, chưa bị xóa; nếu không -> `400 INVALID_RECEIPT`, không ghi gì.
  * Mốc chỉ đi qua một đoạn tin **đều được nêu tên**. Server lấy các tin của phía kia nằm giữa mốc hiện tại và tin lớn nhất được nêu; dừng ở tin đầu tiên không được nêu. Tin người nhận đã xóa phía mình không chặn đoạn.
  * Chưa có mốc: bắt đầu từ tin được nêu đầu tiên (phần trước đó là lịch sử).
* `POST /read` body `{ "messageId": ".." }` -> `ReceiptView` của người gọi. Tin phải do phía kia gửi, chưa xóa phía mình. Đẩy cả `readMark` và `deliveredMark` tới tin đó. Không có body (client cũ) = tin mới nhất của phía kia tại thời điểm xử lý.
* `GET /receipts` -> `ReceiptView` của **người còn lại** (`userId` = người kia, `delivered`, `read`).

`ReceiptView(conversationId, userId, delivered, read)`; mỗi mốc có thể `null`.

Chỉ khi mốc thật sự tiến thì mới phát sự kiện. Không có tin nào bị tạo, `lastMessageAt`, preview và thứ tự inbox không đổi.

## 4. Sự kiện (user queue riêng `/user/queue/chat`)

* `RECEIPT { conversationId, receipt: ReceiptView }` tới **người còn lại** (không tới người vừa ACK). Là marker tuyệt đối, không phải delta: client lấy vị trí mới hơn của từng mốc.
* `TYPING { conversationId, typing: { conversationId, userId, typing } }`.

Không dùng public topic. Mất frame thì client lấy lại bằng `GET /receipts` khi mở thread và mỗi lần reconnect/refetch.

Broker là `simpleBroker` trong process: frame chỉ tới socket nằm trên **cùng instance** với REST/STOMP gây ra nó. Chạy một instance chat-service là đủ; với nhiều instance, mọi sự kiện chat (tin mới, receipt, typing) đều cần relay chung. Đây là giới hạn sẵn có của chat realtime, không riêng receipt.

## 5. Typing

`SEND /app/chat.typing/{conversationId}` payload `{ "typing": true|false }`.

* Người gửi là `Principal` đã xác thực; session là session do server cấp, không phải giá trị client khai.
* Chỉ chấp nhận khi: là participant, `SendPolicy == ALLOWED` (nên **không** có typing khi request chưa accept, đã xóa phải mở lại, hay hết lượt), và không có block. Mọi lỗi (kể cả không kiểm tra được block) -> bỏ im lặng, không báo lý do cho phía kia. Kết quả được nhớ 5s.
* Không lưu, không tạo tin, không đụng unread, `lastMessageAt`, không qua Kafka/outbox.
* Theo từng session: user "đang nhập" khi **bất kỳ** session nào còn hiệu lực; một tab dừng không xóa typing của tab khác. Socket đóng -> bỏ session ngay.

Hằng số (một chỗ định nghĩa, khớp nhau):

| Giá trị | Ở đâu | Ý nghĩa |
|---|---|---|
| `TYPING_HEARTBEAT_MS = 2500` | `src/lib/typing.ts` | client gửi lại tối đa mỗi 2.5s khi còn nhập |
| `TYPING_IDLE_MS = 4000` | `src/lib/typing.ts` | client tự dừng sau 4s không nhập |
| `TypingService.TTL_MS = 7000` | chat-service | server giữ một session tối đa 7s nếu không được gia hạn |
| `TypingService.MIN_INTERVAL_MS = 1000` | chat-service | giới hạn tốc độ mỗi session |
| `TYPING_PEER_TTL_MS = 8000` | `src/lib/typing.ts` | client người xem tự ẩn dấu chấm sau 8s không có frame (lưới an toàn) |

Client dừng typing khi: ngừng nhập, xóa hết nội dung, gửi, rời ô nhập, đổi hội thoại, đóng/thu nhỏ panel, tab ẩn, mất focus, mất quyền gửi (block...), socket ngắt. Sau reconnect không phát lại gì; chỉ bắt đầu lại khi có thao tác nhập thật.
