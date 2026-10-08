package com.tiktok.kafka.dlt;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.kafka.support.KafkaHeaders;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Lists and replays what kafka-lib's error handler parked on a {@code <topic>.DLT}:
 * <pre>
 * make dlt-list   TOPIC=video.video-events.DLT
 * make dlt-replay TOPIC=video.video-events.DLT OFFSET=0:42   one record, DLT partition:offset as listed
 * make dlt-replay TOPIC=video.video-events.DLT ALL=1         every record the dlt-replay group has not been past
 * </pre>
 * Only a failure caused by the record itself lands on a DLT — an outage is retried in place — so
 * replay once the fix that lets the record through is deployed, not before.
 *
 * <p>What makes this more than copying the record back by hand is the order check. Replaying an
 * event after a newer one for the same key reverses them: a takedown replayed after the restore
 * takes the video down again. So a record is skipped while its original partition holds anything
 * later under the same key, and {@code FORCE=1} replays it anyway once someone has looked. Topics
 * here are keyed by the entity, which is what makes the key the thing to compare; on a topic keyed
 * more coarsely (likes, by video) the check is only conservative. {@code ALL=1} moves past what it
 * skips, and says how to replay each one on its own.
 *
 * <p>The record goes back to its original topic with its key, value and headers — eventType
 * included — minus Spring's {@code kafka_dlt-*}, plus {@code dlt-replayed-from}, which is also how
 * a later run recognises its own earlier replay. Every group on that topic receives it again; the
 * ones that handled it the first time drop it on its eventId.
 */
public final class DltReplay {

    static final String GROUP = "dlt-replay";
    static final String REPLAYED_FROM = "dlt-replayed-from";
    private static final String DLT_HEADERS = "kafka_dlt-";
    private static final Duration POLL = Duration.ofMillis(500);

    private final String bootstrapServers;
    private final PrintStream out;

    DltReplay(String bootstrapServers, PrintStream out) {
        this.bootstrapServers = bootstrapServers;
        this.out = out;
    }

    public static void main(String[] args) {
        DltReplay tool = new DltReplay(
                System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"), System.out);
        boolean force = Arrays.asList(args).contains("--force");
        String[] at = args.length > 2 ? args[2].split(":") : new String[0];
        if (args.length >= 2 && args[0].equals("list")) {
            tool.list(args[1]);
        } else if (args.length >= 3 && args[0].equals("replay") && args[2].equals("all")) {
            tool.replayAll(args[1], force);
        } else if (args.length >= 3 && args[0].equals("replay") && at.length == 2) {
            tool.replay(args[1], Integer.parseInt(at[0]), Long.parseLong(at[1]), force);
        } else {
            System.err.println("usage: list <topic>.DLT | replay <topic>.DLT <partition:offset>|all [--force]");
            System.exit(2);
        }
    }

    /** "done" means an ALL=1 run has been past it — replayed, or skipped and reported. */
    void list(String dltTopic) {
        try (KafkaConsumer<byte[], byte[]> dlt = consumer(GROUP)) {
            List<TopicPartition> partitions = partitions(dlt, dltTopic);
            Map<TopicPartition, OffsetAndMetadata> done = dlt.committed(new HashSet<>(partitions));
            dlt.assign(partitions);
            dlt.seekToBeginning(partitions);
            int[] count = {0};
            readToEnd(dlt, partitions, record -> {
                count[0]++;
                OffsetAndMetadata mark = done.get(new TopicPartition(record.topic(), record.partition()));
                out.printf("%d:%d  %s  key=%s  eventType=%s  group=%s  from %s@%s%n",
                        record.partition(), record.offset(),
                        mark != null && record.offset() < mark.offset() ? "done   " : "pending",
                        text(record.key()), header(record, "eventType"),
                        header(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP),
                        originOf(record), longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET));
                out.printf("      %s: %s%n", header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                        firstLine(header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE)));
            });
            out.println(count[0] + " record(s) on " + dltTopic);
        }
    }

    /** One record, by its DLT coordinates. Leaves the dlt-replay group's position alone. */
    void replay(String dltTopic, int partition, long offset, boolean force) {
        try (KafkaConsumer<byte[], byte[]> dlt = consumer(null);
             KafkaConsumer<byte[], byte[]> scan = consumer(null);
             KafkaProducer<byte[], byte[]> producer = producer()) {
            TopicPartition tp = new TopicPartition(dltTopic, partition);
            dlt.assign(List.of(tp));
            dlt.seek(tp, offset);
            List<ConsumerRecord<byte[], byte[]>> found = new ArrayList<>(1);
            readToEnd(dlt, List.of(tp), record -> {
                if (record.offset() == offset) {
                    found.add(record);
                }
            });
            if (found.isEmpty()) {
                out.println("no record at " + tp + "@" + offset);
            } else {
                replay(found.get(0), scan, producer, force);
            }
        }
    }

    /** Everything after the dlt-replay group's position, committing past each record as it goes. */
    void replayAll(String dltTopic, boolean force) {
        try (KafkaConsumer<byte[], byte[]> dlt = consumer(GROUP);
             KafkaConsumer<byte[], byte[]> scan = consumer(null);
             KafkaProducer<byte[], byte[]> producer = producer()) {
            List<TopicPartition> partitions = partitions(dlt, dltTopic);
            Map<TopicPartition, OffsetAndMetadata> done = dlt.committed(new HashSet<>(partitions));
            dlt.assign(partitions);
            for (TopicPartition tp : partitions) {
                OffsetAndMetadata mark = done.get(tp);
                if (mark == null) {
                    dlt.seekToBeginning(List.of(tp));
                } else {
                    dlt.seek(tp, mark.offset());
                }
            }
            int[] count = {0};
            readToEnd(dlt, partitions, record -> {
                count[0]++;
                replay(record, scan, producer, force);
                dlt.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1)));
            });
            out.println(count[0] + " record(s) on " + dltTopic + " since the last ALL=1 run");
        }
    }

    private void replay(ConsumerRecord<byte[], byte[]> record, KafkaConsumer<byte[], byte[]> scan,
                        KafkaProducer<byte[], byte[]> producer, boolean force) {
        String self = record.topic() + "-" + record.partition() + "@" + record.offset();
        String originTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Integer originPartition = intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        Long originOffset = longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        if (originTopic == null || originPartition == null || originOffset == null) {
            out.println("skipped " + self + ": no kafka_dlt-original-* headers, so not a record kafka-lib parked");
            return;
        }

        String[] later = laterUnderSameKey(scan, new TopicPartition(originTopic, originPartition),
                originOffset, record.key(), self);
        if (later[1] != null) {
            out.println("skipped " + self + ": already replayed as " + later[1]);
            return;
        }
        if (later[0] != null && !force) {
            out.println("skipped " + self + ": " + later[0] + ". If replaying it after that is right: OFFSET="
                    + record.partition() + ":" + record.offset() + " FORCE=1");
            return;
        }

        RecordHeaders headers = new RecordHeaders();
        for (Header h : record.headers()) {
            if (!h.key().startsWith(DLT_HEADERS) && !h.key().equals(REPLAYED_FROM)) {
                headers.add(h);
            }
        }
        headers.add(REPLAYED_FROM, self.getBytes(StandardCharsets.UTF_8));
        RecordMetadata sent = await(producer.send(
                new ProducerRecord<>(originTopic, null, record.key(), record.value(), headers)));
        out.println("replayed " + self + " -> " + sent.topic() + "-" + sent.partition() + "@" + sent.offset());
    }

    /**
     * What the original partition holds after the failed record under its key: [0] the first later
     * record that is not this one's replay, or why that cannot be known; [1] this one's earlier
     * replay, if there is one.
     *
     * <p>ponytail: reads the partition from the failed offset to its end, fine for a hand-run tool
     * on these topics; bound it by time if a topic grows to where that takes minutes.
     */
    private static String[] laterUnderSameKey(KafkaConsumer<byte[], byte[]> scan, TopicPartition origin,
                                              long offset, byte[] key, String self) {
        String[] later = new String[2];
        if (key == null) {
            return later; // no key, no order between this and anything else
        }
        scan.assign(List.of(origin));
        long logStart = scan.beginningOffsets(List.of(origin)).get(origin);
        if (logStart > offset + 1) {
            later[0] = "retention already dropped records after " + origin + "@" + offset
                    + ", so whether a later one shares its key cannot be checked";
        }
        scan.seek(origin, Math.max(offset + 1, logStart));
        readToEnd(scan, List.of(origin), record -> {
            if (!Arrays.equals(record.key(), key)) {
                return;
            }
            if (self.equals(header(record, REPLAYED_FROM))) {
                later[1] = origin + "@" + record.offset();
            } else if (later[0] == null) {
                later[0] = origin + "@" + record.offset() + " holds a later " + header(record, "eventType")
                        + " for key " + text(key);
            }
        });
        return later;
    }

    private static void readToEnd(KafkaConsumer<byte[], byte[]> consumer, Collection<TopicPartition> partitions,
                                  Consumer<ConsumerRecord<byte[], byte[]>> action) {
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
        while (end.entrySet().stream().anyMatch(e -> consumer.position(e.getKey()) < e.getValue())) {
            for (ConsumerRecord<byte[], byte[]> record : consumer.poll(POLL)) {
                if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))) {
                    action.accept(record);
                }
            }
        }
    }

    private static List<TopicPartition> partitions(KafkaConsumer<byte[], byte[]> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null || infos.isEmpty()) {
            throw new IllegalArgumentException("No topic " + topic);
        }
        return infos.stream().map(p -> new TopicPartition(topic, p.partition())).toList();
    }

    private KafkaConsumer<byte[], byte[]> consumer(String group) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        if (group != null) {
            props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        }
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        return new KafkaConsumer<>(props, new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    private KafkaProducer<byte[], byte[]> producer() {
        return new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all"), new ByteArraySerializer(), new ByteArraySerializer());
    }

    private static RecordMetadata await(Future<RecordMetadata> send) {
        try {
            return send.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the broker", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The broker refused the replay", e.getCause());
        }
    }

    private static String originOf(ConsumerRecord<byte[], byte[]> record) {
        return header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC) + "-" + intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Integer intHeader(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long longHeader(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : ByteBuffer.wrap(header.value()).getLong();
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return null;
        }
        String line = message.lines().findFirst().orElse("");
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
