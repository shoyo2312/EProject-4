package com.tiktok.kafka.dlt;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static com.tiktok.kafka.dlt.DeadLetterFixtures.header;
import static com.tiktok.kafka.dlt.DeadLetterFixtures.park;
import static com.tiktok.kafka.dlt.DeadLetterFixtures.read;
import static com.tiktok.kafka.dlt.DeadLetterFixtures.send;
import static com.tiktok.kafka.dlt.DeadLetterFixtures.topics;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** The make dlt-* tool against a real broker: what it sends, and what it tells whoever ran it. */
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
        assertThat(header(replayed, DeadLetters.REPLAYED_FROM)).isEqualTo(topic + ".DLT-0@0");
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
}
