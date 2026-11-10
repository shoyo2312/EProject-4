package com.tiktok.kafka.dlt;

import com.tiktok.kafka.dlt.ReplayResult.Outcome;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.tiktok.kafka.dlt.DeadLetterFixtures.park;
import static com.tiktok.kafka.dlt.DeadLetterFixtures.read;
import static com.tiktok.kafka.dlt.DeadLetterFixtures.send;
import static com.tiktok.kafka.dlt.DeadLetterFixtures.topics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the admin console reads and does through admin-service, against a real broker. */
@EmbeddedKafka
class DeadLettersTest {

    @Test
    void aTopicCountsWhatItHoldsAndWhatIsStillPending(EmbeddedKafkaBroker broker) throws Exception {
        String topic = topics(broker, "letters-count");
        park(broker, topic, send(broker, topic, "v1", "VideoPublishedEvent"), "v1", "VideoPublishedEvent");
        park(broker, topic, send(broker, topic, "v2", "VideoPublishedEvent"), "v2", "VideoPublishedEvent");
        DeadLetters deadLetters = deadLetters(broker);

        assertThat(deadLetters.topics()).filteredOn(t -> t.name().equals(topic + ".DLT")).singleElement()
                .satisfies(t -> {
                    assertThat(t.originTopic()).isEqualTo(topic);
                    assertThat(t.retained()).isEqualTo(2);
                    assertThat(t.pending()).isEqualTo(2);
                    assertThat(t.latestAt()).isNotNull();
                });

        assertThat(deadLetters.replayPending(topic + ".DLT", false, 1, result -> { })).isEqualTo(1);

        DeadLetterTopic after = deadLetters.topic(topic + ".DLT");
        assertThat(after.retained()).isEqualTo(2);
        assertThat(after.pending()).isEqualTo(1);
    }

    /** The listener container wraps what the listener threw; the cause is what says what went wrong. */
    @Test
    void theNewestRecordsComeFirstWithWhereAndWhyTheyFailed(EmbeddedKafkaBroker broker) throws Exception {
        String topic = topics(broker, "letters-latest");
        park(broker, topic, send(broker, topic, "v1", "VideoPublishedEvent"), "v1", "VideoPublishedEvent");
        park(broker, topic, send(broker, topic, "v2", "VideoDeletedEvent"), "v2", "VideoDeletedEvent",
                new IllegalStateException("Listener failed",
                        new IllegalArgumentException("Unexpected character ('x')\n at [Source: ...]")));
        DeadLetters deadLetters = deadLetters(broker);

        List<DeadLetter> latest = deadLetters.latest(topic + ".DLT", 10);

        assertThat(latest).extracting(DeadLetter::offset).containsExactly(1L, 0L);
        DeadLetter newest = latest.get(0);
        assertThat(newest.key()).isEqualTo("v2");
        assertThat(newest.eventType()).isEqualTo("VideoDeletedEvent");
        assertThat(newest.origin()).isEqualTo(topic + "-0@1");
        assertThat(newest.error()).isEqualTo("java.lang.IllegalArgumentException: Unexpected character ('x')");
        assertThat(newest.pending()).isTrue();
        assertThat(latest.get(1).error()).isEqualTo("java.lang.IllegalStateException: bug");

        assertThat(deadLetters.latest(topic + ".DLT", 1)).extracting(DeadLetter::offset).containsExactly(1L);
    }

    @Test
    void replayingEveryPendingRecordReportsEachOne(EmbeddedKafkaBroker broker) throws Exception {
        String topic = topics(broker, "letters-pending");
        park(broker, topic, send(broker, topic, "v1", "VideoTakenDownEvent"), "v1", "VideoTakenDownEvent");
        send(broker, topic, "v1", "VideoRestoredEvent");
        park(broker, topic, send(broker, topic, "v2", "VideoPublishedEvent"), "v2", "VideoPublishedEvent");
        DeadLetters deadLetters = deadLetters(broker);

        List<ReplayResult> results = new ArrayList<>();
        assertThat(deadLetters.replayPending(topic + ".DLT", false, 10, results::add)).isEqualTo(2);

        assertThat(results).extracting(ReplayResult::outcome).containsExactly(Outcome.HELD_BACK, Outcome.REPLAYED);
        assertThat(results.get(0).detail()).contains("a later VideoRestoredEvent");
        assertThat(results.get(1).detail()).isEqualTo(topic + "-0@3");
        assertThat(results).allSatisfy(result -> assertThat(result.parkedAt()).isNotNull());
        assertThat(read(broker, topic)).hasSize(4);
        assertThat(deadLetters.topic(topic + ".DLT").pending()).isZero();
    }

    @Test
    void aRecordThatIsNotThereIsNotFound(EmbeddedKafkaBroker broker) throws Exception {
        String topic = topics(broker, "letters-missing");
        park(broker, topic, send(broker, topic, "v1", "VideoPublishedEvent"), "v1", "VideoPublishedEvent");
        DeadLetters deadLetters = deadLetters(broker);

        assertThat(deadLetters.replay(topic + ".DLT", 0, 99, false).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(deadLetters.replay(topic + ".DLT", 5, 0, false).outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(read(broker, topic)).hasSize(1);
    }

    @Test
    void aTopicTheBrokerDoesNotHaveIsUnknown(EmbeddedKafkaBroker broker) {
        assertThatThrownBy(() -> deadLetters(broker).topic("letters-never.DLT"))
                .isInstanceOf(UnknownTopicOrPartitionException.class);
    }

    /** Replay writes to whatever the record's headers name; only a DLT's records were put there by kafka-lib. */
    @Test
    void onlyADeadLetterTopicIsRead(EmbeddedKafkaBroker broker) {
        assertThatThrownBy(() -> deadLetters(broker).latest("video.video-events", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deadLetters(broker).replay(".DLT", 0, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeadLetters deadLetters(EmbeddedKafkaBroker broker) {
        return new DeadLetters(Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()),
                Duration.ofSeconds(30));
    }
}
