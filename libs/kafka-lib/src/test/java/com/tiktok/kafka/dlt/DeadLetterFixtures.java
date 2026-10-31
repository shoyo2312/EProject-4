package com.tiktok.kafka.dlt;

import com.tiktok.kafka.config.KafkaConsumerAutoConfiguration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * DLT records written by the DeadLetterPublishingRecoverer kafka-lib's error handler uses, so the
 * headers the tests read are the ones a real failure leaves.
 */
final class DeadLetterFixtures {

    private DeadLetterFixtures() {
    }

    static String topics(EmbeddedKafkaBroker broker, String topic) {
        broker.addTopics(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + ".DLT", 1, (short) 1));
        return topic;
    }

    /** The original event, as its producer sent it. */
    static long send(EmbeddedKafkaBroker broker, String topic, String key, String eventType) throws Exception {
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new ByteArraySerializer(), new ByteArraySerializer())) {
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, key.getBytes(UTF_8), payload(key));
            record.headers().add("eventType", eventType.getBytes(UTF_8));
            return producer.send(record).get().offset();
        }
    }

    static void park(EmbeddedKafkaBroker broker, String topic, long offset, String key, String eventType) {
        park(broker, topic, offset, key, eventType, new IllegalStateException("bug"));
    }

    /** What kafka-lib's error handler does with a record that failed on its own content. */
    static void park(EmbeddedKafkaBroker broker, String topic, long offset, String key, String eventType,
                     Exception failure) {
        DefaultKafkaProducerFactory<byte[], byte[]> factory = new DefaultKafkaProducerFactory<>(
                KafkaTestUtils.producerProps(broker), new ByteArraySerializer(), new ByteArraySerializer());
        try {
            ConsumerRecord<byte[], byte[]> failed = new ConsumerRecord<>(topic, 0, offset, key.getBytes(UTF_8), payload(key));
            failed.headers().add("eventType", eventType.getBytes(UTF_8));
            KafkaConsumerAutoConfiguration.deadLetterRecoverer(new KafkaTemplate<>(factory)).accept(failed, failure);
        } finally {
            factory.destroy();
        }
    }

    static List<ConsumerRecord<byte[], byte[]>> read(EmbeddedKafkaBroker broker, String topic) {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(
                KafkaTestUtils.consumerProps("reader", "false", broker),
                new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            TopicPartition tp = new TopicPartition(topic, 0);
            consumer.assign(List.of(tp));
            consumer.seekToBeginning(List.of(tp));
            long end = consumer.endOffsets(List.of(tp)).get(tp);
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            while (consumer.position(tp) < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }

    static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        return new String(record.headers().lastHeader(name).value(), UTF_8);
    }

    private static byte[] payload(String key) {
        return ("{\"videoId\":\"" + key + "\"}").getBytes(UTF_8);
    }
}
