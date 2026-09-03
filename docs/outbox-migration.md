# Migrate một `OutboxPublisher` sang `OutboxDispatcher`

Việc còn tồn: `inventory-service`, `order-service`, `payment-service`.
Đã xong: `auth-service`, `admin-service`, `video-service`, `product-service`.

## Vì sao phải làm

Bốn service trên vẫn viết:

```java
kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
event.markPublished();
```

`send()` là async — nó chỉ throw đồng bộ khi serialize lỗi hoặc buffer producer đầy. Broker từ
chối record thì row vẫn bị đánh dấu published, mà query poll
(`findTop100ByPublishedAtIsNullOrderByCreatedAtAsc`) bỏ qua row đã đánh dấu → **event mất vĩnh
viễn**. Đây đúng là thứ outbox sinh ra để chống.

Với `order`/`payment`/`inventory` hậu quả nặng hơn `video`: mất một `OrderCreatedEvent` hay
`PaymentCompletedEvent` là treo saga giữa chừng, order kẹt `PENDING` và tồn kho không được
release.

## Các bước

**1. Thêm dependency** (`services/<x>-service/pom.xml`):

```xml
<dependency>
    <groupId>com.tiktok</groupId>
    <artifactId>kafka-lib</artifactId>
</dependency>
```

**2. ⚠️ Kiểm tra service có `@KafkaListener` không.** `order`, `payment`, `inventory` **có**.
Với chúng, thêm `kafka-lib` đồng thời bật luôn `DefaultErrorHandler` + DLQ
(`KafkaConsumerAutoConfiguration`): consumer chuyển từ retry-vô-hạn sang retry 3 lần rồi đẩy sang
`<topic>.DLT`. Đó là thay đổi hành vi runtime, **không phải** phần của migration outbox — hoặc
nhận nó một cách có ý thức (nhớ tạo/giám sát topic `.DLT`), hoặc tách thành PR riêng.
`product-service` không có consumer nên không dính (đã migrate).

**3. Đổi field**: bỏ `KafkaTemplate<String, String>`, inject `OutboxDispatcher`.

**4. Viết lại `publishPending()`** — mẫu, xem
`services/auth-service/.../event/producer/OutboxPublisher.java`:

```java
@Scheduled(fixedDelay = 5000)
@Transactional
public void publishPending() {
    List<OutboxEvent> pending = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
    if (pending.isEmpty()) {
        return;
    }

    int published = outboxDispatcher.dispatch(
            pending,
            event -> new ProducerRecord<>(topicFor(event), event.getAggregateId(), event.getPayload()),
            OutboxEvent::markPublished);

    if (published < pending.size()) {
        log.warn("Published {}/{} outbox events, the rest stay pending for the next poll",
                published, pending.size());
    }
}
```

`order`/`payment`/`inventory` đã có sẵn map topic-theo-event-type — chuyển nguyên vào hàm
`toRecord`. Nếu `TOPIC_BY_EVENT_TYPE.get()` trả `null` thì `ProducerRecord` sẽ ném NPE, và
dispatcher log rồi bỏ qua đúng row đó thay vì làm hỏng cả batch — nhưng nên check tường minh và
log rõ hơn.

**5. Test.** Đảo bài test khẳng định hành vi cũ nếu có, và thêm ca "broker từ chối → row vẫn
pending". Mẫu:
`services/video-service/src/test/java/com/tiktok/videoservice/event/producer/VideoEventPublisherTest.java`
(dùng `OutboxDispatcher` thật trên `KafkaOperations` mock, vì quy tắc cần test nằm trong
dispatcher).

## Đánh đổi đã chọn

Row không được ack ở lại chờ poll sau → có thể gửi **trùng** thay vì **mất**. Mọi consumer trong
repo đã idempotent (claim `eventId` trước khi xử lý, xem `CLAUDE.md` §Kafka) nên trùng là vô hại,
còn mất thì không cứu được.

Timeout chờ ack: `tiktok.kafka.outbox.ack-timeout`, mặc định 30s — cố ý thấp hơn nhiều
`delivery.timeout.ms` (mặc định 120s) của producer, để lúc broker chết thì scheduler thread và
transaction JPA bao quanh không bị giữ hàng phút.
