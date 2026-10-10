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
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real broker, with the DLT records written by the DeadLetterPublishingRecoverer
 * kafka-lib's error handler uses, so the headers read here are the ones a real failure leaves.
 */
@EmbeddedKafka
class DltReplayTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @Test
    void aParkedRecordGoesBackWithItsHeadersButNotTheDltOnes(EmbeddedKafkaBroker broker) throws Exception {
        String topic = topics(broker, "replay-one");
        park(broker, topic, send(broker, topic, "v1", "VideoTakenDownEvent"), "v1", "VideoTakenDownEvent");

        tool(broker).replay(topic + ".DLT", 0, 0, false);

        List<ConsumerRecord<byte[], byte[]>> records = read(broker, topic);
        assertThat(records).hasSize(2);
        ConsumerRecord<byte[], byte[]> replayed = records.get(1);
        assertThat(new String(replayed.key(), UTF_8)).isEqualTo("v1");
        assertThat(replayed.value()).isEqualTo(records.get(0).value());
        assertThat(header(replayed, "eventType")).isEqualTo("VideoTakenDownEvent");
        assertThat(header(replayed, DltReplay.REPLAYED_FROM)).isEqualTo(topic + ".DLT-0@0");
        assertThat(replayed.headers()).noneMatch(h -> h.key().startsWith("kafka_dlt-"));
    }

    /** The restore came after the takedown failed: replaying the takedown now would undo it. */
    @Test
    void aLaterRecordUnderTheSameKeyHoldsTheReplayBackUntilForced(EmbeddedKafkaBroker broker) throws Exception {
        String topic = topics(broker, "replay-later");
        park(broker, topic, send(broker, topic, "v1", "VideoTakenDownEvent"), "v1", "VideoTakenDownEvent");
        send(broker, topic, "v1", "VideoRestoredEvent");

        tool(broker).replay(topic + ".DLT", 0, 0, false);
        assertThat(read(broker, topic)).hasSize(2);
        assertThat(output.toString(UTF_8)).contains("skipped", "a later VideoRestoredEvent", "FORCE=1");

        tool(broker).replay(topic + ".DLT", 0, 0, true);
        assertThat(read(broker, topic)).hasSize(3);
    }

    /** A second run neither replays again nor takes the first run's replay for a newer event. */
    @Test
    void eachRecordIsReplayedOnce(EmbeddedKafkaBroker broker) throws Exception {
        String topic = topics(broker, "replay-all");
        park(broker, topic, send(broker, topic, "v1", "VideoPublishedEvent"), "v1", "VideoPublishedEvent");
        park(broker, topic, send(broker, topic, "v2", "VideoPublishedEvent"), "v2", "VideoPublishedEvent");

        tool(broker).replayAll(topic + ".DLT", false);
        tool(broker).replayAll(topic + ".DLT", false);
        tool(broker).replay(topic + ".DLT", 0, 0, false);

        assertThat(read(broker, topic)).hasSize(4);
        assertThat(output.toString(UTF_8)).contains("already replayed as " + topic + "-0@2");
    }

    private DltReplay tool(EmbeddedKafkaBroker broker) {
        return new DltReplay(broker.getBrokersAsString(), new PrintStream(output, true, UTF_8));
    }

    private static String topics(EmbeddedKafkaBroker broker, String topic) {
        broker.addTopics(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + ".DLT", 1, (short) 1));
        return topic;
    }

    /** The original event, as its producer sent it. */
    private static long send(EmbeddedKafkaBroker broker, String topic, String key, String eventType) throws Exception {
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(KafkaTestUtils.producerProps(broker),
                new ByteArraySerializer(), new ByteArraySerializer())) {
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, key.getBytes(UTF_8), payload(key));
            record.headers().add("eventType", eventType.getBytes(UTF_8));
            return producer.send(record).get().offset();
        }
    }

    /** What kafka-lib's error handler does with a record that failed on its own content. */
    private static void park(EmbeddedKafkaBroker broker, String topic, long offset, String key, String eventType) {
        DefaultKafkaProducerFactory<byte[], byte[]> factory = new DefaultKafkaProducerFactory<>(
                KafkaTestUtils.producerProps(broker), new ByteArraySerializer(), new ByteArraySerializer());
        try {
            ConsumerRecord<byte[], byte[]> failed = new ConsumerRecord<>(topic, 0, offset, key.getBytes(UTF_8), payload(key));
            failed.headers().add("eventType", eventType.getBytes(UTF_8));
            KafkaConsumerAutoConfiguration.deadLetterRecoverer(new KafkaTemplate<>(factory))
                    .accept(failed, new IllegalStateException("bug"));
        } finally {
            factory.destroy();
        }
    }

    private static List<ConsumerRecord<byte[], byte[]>> read(EmbeddedKafkaBroker broker, String topic) {
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

    private static byte[] payload(String key) {
        return ("{\"videoId\":\"" + key + "\"}").getBytes(UTF_8);
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        return new String(record.headers().lastHeader(name).value(), UTF_8);
    }
}
