package com.tiktok.kafka.dlt;

import com.tiktok.kafka.dlt.ReplayResult.Outcome;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Reads and replays what kafka-lib's error handler parked on a {@code <topic>.DLT} — behind both
 * {@code make dlt-list}/{@code make dlt-replay} ({@link DltReplay}) and admin-service's console.
 * Only a failure caused by the record itself lands on a DLT — an outage is retried in place — so
 * replay once the fix that lets the record through is deployed, not before.
 *
 * <p>What makes this more than copying the record back by hand is the order check. Replaying an
 * event after a newer one for the same key reverses them: a takedown replayed after the restore
 * takes the video down again. So a record is held back while its original partition holds anything
 * later under the same key, and {@code force} replays it anyway once someone has looked. Topics
 * here are keyed by the entity, which is what makes the key the thing to compare; on a topic keyed
 * more coarsely (likes, by video) the check is only conservative.
 *
 * <p>The record goes back to its original topic with its key, value and headers — eventType
 * included — minus Spring's {@code kafka_dlt-*}, plus {@code dlt-replayed-from}, which is also how
 * a later replay recognises an earlier one. Every group on that topic receives it again; the ones
 * that handled it the first time drop it on its eventId.
 *
 * <p>Every call opens its own clients and closes them: KafkaConsumer is not thread-safe, and
 * nothing here runs often enough to be worth keeping one open.
 */
public final class DeadLetters {

    public static final String SUFFIX = ".DLT";
    static final String GROUP = "dlt-replay";
    static final String REPLAYED_FROM = "dlt-replayed-from";
    private static final String DLT_HEADERS = "kafka_dlt-";
    private static final String CAUSED_BY = "Caused by: ";
    private static final Duration POLL = Duration.ofMillis(500);
    private static final long MAX_API_TIMEOUT_MILLIS = 30_000;
    private static final int ERROR_LENGTH = 300;
    private static final Comparator<DeadLetter> NEWEST_FIRST = Comparator.comparing(DeadLetter::parkedAt)
            .thenComparingInt(DeadLetter::partition).thenComparingLong(DeadLetter::offset).reversed();

    private final Map<String, Object> clientConfig;
    private final Duration timeout;

    /**
     * @param clientConfig what the broker asks of any client: bootstrap servers, SASL. Serializers,
     *                     group and commit settings are this class's own
     * @param timeout      how long one call may read for. Without it, a broker that stops
     *                     answering midway keeps the caller polling for good
     */
    public DeadLetters(Map<String, Object> clientConfig, Duration timeout) {
        this.clientConfig = new HashMap<>(clientConfig);
        this.clientConfig.remove(ConsumerConfig.CLIENT_ID_CONFIG);
        this.timeout = timeout;
    }

    public static boolean isDeadLetterTopic(String topic) {
        return topic != null && topic.length() > SUFFIX.length() && topic.endsWith(SUFFIX);
    }

    /** Every DLT on the broker. One only exists once something has been parked on it. */
    public List<DeadLetterTopic> topics() {
        Instant deadline = deadline();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(GROUP)) {
            Map<String, List<TopicPartition>> dlts = new TreeMap<>();
            consumer.listTopics().forEach((name, infos) -> {
                if (isDeadLetterTopic(name)) {
                    dlts.put(name, infos.stream().map(p -> new TopicPartition(name, p.partition())).toList());
                }
            });
            return describe(consumer, dlts, deadline);
        }
    }

    /** @throws UnknownTopicOrPartitionException when the broker has no such topic */
    public DeadLetterTopic topic(String dltTopic) {
        requireDeadLetterTopic(dltTopic);
        Instant deadline = deadline();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(GROUP)) {
            return describe(consumer, Map.of(dltTopic, partitions(consumer, dltTopic)), deadline).get(0);
        }
    }

    /** The newest {@code limit} records, newest first. */
    public List<DeadLetter> latest(String dltTopic, int limit) {
        requireDeadLetterTopic(dltTopic);
        Instant deadline = deadline();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(GROUP)) {
            List<TopicPartition> partitions = partitions(consumer, dltTopic);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            Map<TopicPartition, OffsetAndMetadata> done = consumer.committed(new HashSet<>(partitions));
            consumer.assign(partitions);
            partitions.forEach(tp -> consumer.seek(tp, Math.max(begin.get(tp), end.get(tp) - limit)));
            List<DeadLetter> found = new ArrayList<>();
            read(consumer, partitions, deadline, record -> {
                found.add(deadLetter(record, done));
                return true;
            });
            found.sort(NEWEST_FIRST);
            return found.size() > limit ? List.copyOf(found.subList(0, limit)) : found;
        }
    }

    /** Every record the broker still holds. */
    public void forEach(String dltTopic, Consumer<DeadLetter> action) {
        requireDeadLetterTopic(dltTopic);
        Instant deadline = deadline();
        try (KafkaConsumer<byte[], byte[]> consumer = consumer(GROUP)) {
            List<TopicPartition> partitions = partitions(consumer, dltTopic);
            Map<TopicPartition, OffsetAndMetadata> done = consumer.committed(new HashSet<>(partitions));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            read(consumer, partitions, deadline, record -> {
                action.accept(deadLetter(record, done));
                return true;
            });
        }
    }

    /** One record, by its DLT coordinates. Leaves the dlt-replay group's position alone. */
    public ReplayResult replay(String dltTopic, int partition, long offset, boolean force) {
        requireDeadLetterTopic(dltTopic);
        Instant deadline = deadline();
        ReplayResult notFound = new ReplayResult(dltTopic, partition, offset, null, Outcome.NOT_FOUND, null);
        try (KafkaConsumer<byte[], byte[]> dlt = consumer(null);
             KafkaConsumer<byte[], byte[]> scan = consumer(null);
             KafkaProducer<byte[], byte[]> producer = producer()) {
            TopicPartition tp = new TopicPartition(dltTopic, partition);
            if (!partitions(dlt, dltTopic).contains(tp)) {
                return notFound;
            }
            dlt.assign(List.of(tp));
            if (offset < dlt.beginningOffsets(List.of(tp)).get(tp)) {
                return notFound;
            }
            dlt.seek(tp, offset);
            List<ConsumerRecord<byte[], byte[]>> found = new ArrayList<>(1);
            read(dlt, List.of(tp), deadline, record -> {
                if (record.offset() == offset) {
                    found.add(record);
                }
                return false;
            });
            return found.isEmpty() ? notFound : replay(found.get(0), scan, producer, force, deadline);
        }
    }

    /**
     * The records past the dlt-replay group's position, up to {@code max}. The group moves past
     * each one as it is handled — replayed or held back — so the next call carries on after it,
     * and {@code each} hears of it right then: whatever was done before a later failure has
     * already been reported. Stops early, without throwing, when the timeout runs out.
     *
     * @return how many records were handled
     */
    public int replayPending(String dltTopic, boolean force, int max, Consumer<ReplayResult> each) {
        requireDeadLetterTopic(dltTopic);
        Instant deadline = deadline();
        int[] handled = {0};
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
            read(dlt, partitions, deadline, record -> {
                if (handled[0] >= max) {
                    return false;
                }
                ReplayResult result = replay(record, scan, producer, force, deadline);
                dlt.commitSync(Map.of(new TopicPartition(record.topic(), record.partition()),
                        new OffsetAndMetadata(record.offset() + 1)));
                handled[0]++;
                each.accept(result);
                return true;
            });
        } catch (TimeoutException e) {
            if (handled[0] == 0) {
                throw e;
            }
        }
        return handled[0];
    }

    private ReplayResult replay(ConsumerRecord<byte[], byte[]> record, KafkaConsumer<byte[], byte[]> scan,
                                KafkaProducer<byte[], byte[]> producer, boolean force, Instant deadline) {
        String originTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Integer originPartition = intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        Long originOffset = longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        if (originTopic == null || originPartition == null || originOffset == null) {
            return result(record, Outcome.NOT_PARKED, null);
        }

        String self = coordinates(record);
        String[] later = laterUnderSameKey(scan, new TopicPartition(originTopic, originPartition),
                originOffset, record.key(), self, deadline);
        if (later[1] != null) {
            return result(record, Outcome.ALREADY_REPLAYED, later[1]);
        }
        if (later[0] != null && !force) {
            return result(record, Outcome.HELD_BACK, later[0]);
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
        return result(record, Outcome.REPLAYED, sent.topic() + "-" + sent.partition() + "@" + sent.offset());
    }

    /**
     * What the original partition holds after the failed record under its key: [0] the first later
     * record that is not this one's replay, or why that cannot be known; [1] this one's earlier
     * replay, if there is one.
     *
     * <p>ponytail: reads the partition from the failed offset to its end, once per record replayed.
     * Bounded by the timeout; read it in one pass per partition if topics grow to where that is hit.
     */
    private static String[] laterUnderSameKey(KafkaConsumer<byte[], byte[]> scan, TopicPartition origin,
                                              long offset, byte[] key, String self, Instant deadline) {
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
        read(scan, List.of(origin), deadline, record -> {
            if (!Arrays.equals(record.key(), key)) {
                return true;
            }
            if (self.equals(header(record, REPLAYED_FROM))) {
                later[1] = origin + "@" + record.offset();
                return false;
            }
            if (later[0] == null) {
                later[0] = origin + "@" + record.offset() + " holds a later " + header(record, "eventType")
                        + " for key " + text(key);
            }
            return true;
        });
        return later;
    }

    private List<DeadLetterTopic> describe(KafkaConsumer<byte[], byte[]> consumer,
                                           Map<String, List<TopicPartition>> topics, Instant deadline) {
        List<TopicPartition> all = topics.values().stream().flatMap(List::stream).toList();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<TopicPartition, Long> begin = consumer.beginningOffsets(all);
        Map<TopicPartition, Long> end = consumer.endOffsets(all);
        Map<TopicPartition, OffsetAndMetadata> done = consumer.committed(new HashSet<>(all));

        // Each partition's newest record says when the topic last took one.
        List<TopicPartition> nonEmpty = all.stream().filter(tp -> end.get(tp) > begin.get(tp)).toList();
        Map<String, Instant> latest = new HashMap<>();
        consumer.assign(nonEmpty);
        nonEmpty.forEach(tp -> consumer.seek(tp, end.get(tp) - 1));
        read(consumer, nonEmpty, deadline, record -> {
            latest.merge(record.topic(), Instant.ofEpochMilli(record.timestamp()),
                    (a, b) -> a.isAfter(b) ? a : b);
            return true;
        });

        List<DeadLetterTopic> described = new ArrayList<>(topics.size());
        topics.forEach((name, partitions) -> {
            long retained = 0;
            long pending = 0;
            for (TopicPartition tp : partitions) {
                long first = begin.get(tp);
                OffsetAndMetadata mark = done.get(tp);
                retained += end.get(tp) - first;
                pending += Math.max(0, end.get(tp) - (mark == null ? first : Math.max(first, mark.offset())));
            }
            described.add(new DeadLetterTopic(name, name.substring(0, name.length() - SUFFIX.length()),
                    retained, pending, latest.get(name)));
        });
        return described;
    }

    /**
     * Each partition from its position to the end offset it had when the read began, until
     * {@code each} returns false.
     */
    private static void read(KafkaConsumer<byte[], byte[]> consumer, Collection<TopicPartition> partitions,
                             Instant deadline, Predicate<ConsumerRecord<byte[], byte[]>> each) {
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
        while (end.entrySet().stream().anyMatch(e -> consumer.position(e.getKey()) < e.getValue())) {
            if (Instant.now().isAfter(deadline)) {
                throw new TimeoutException("Gave up reading " + partitions + " before its end");
            }
            for (ConsumerRecord<byte[], byte[]> record : consumer.poll(POLL)) {
                if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))
                        && !each.test(record)) {
                    return;
                }
            }
        }
    }

    private static DeadLetter deadLetter(ConsumerRecord<byte[], byte[]> record,
                                         Map<TopicPartition, OffsetAndMetadata> done) {
        OffsetAndMetadata mark = done.get(new TopicPartition(record.topic(), record.partition()));
        String originTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Integer originPartition = intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        Long originOffset = longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);
        String origin = originTopic == null || originPartition == null || originOffset == null
                ? null : originTopic + "-" + originPartition + "@" + originOffset;
        return new DeadLetter(record.topic(), record.partition(), record.offset(),
                Instant.ofEpochMilli(record.timestamp()), text(record.key()), header(record, "eventType"),
                origin, header(record, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP), error(record),
                mark == null || record.offset() >= mark.offset());
    }

    /**
     * The deepest cause in the stack trace header. The listener container wraps whatever the
     * listener threw, so the exception's own class and message are usually Spring's and say only
     * that a listener failed; they are the fallback for a record parked without a trace.
     */
    static String error(ConsumerRecord<byte[], byte[]> record) {
        String trace = header(record, KafkaHeaders.DLT_EXCEPTION_STACKTRACE);
        String cause = trace == null ? null : trace.lines()
                .filter(line -> line.startsWith(CAUSED_BY))
                .reduce((first, second) -> second)
                .map(line -> line.substring(CAUSED_BY.length()))
                .orElse(null);
        if (cause == null) {
            String fqcn = header(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
            if (fqcn == null) {
                return null;
            }
            String message = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);
            cause = message == null ? fqcn : fqcn + ": " + message;
        }
        String line = cause.lines().findFirst().orElse("").strip();
        return line.length() > ERROR_LENGTH ? line.substring(0, ERROR_LENGTH) + "…" : line;
    }

    private Instant deadline() {
        return Instant.now().plus(timeout);
    }

    private static void requireDeadLetterTopic(String topic) {
        if (!isDeadLetterTopic(topic)) {
            throw new IllegalArgumentException(topic + " is not a dead-letter topic");
        }
    }

    private KafkaConsumer<byte[], byte[]> consumer(String group) {
        Map<String, Object> props = new HashMap<>(clientConfig);
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        if (group != null) {
            props.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        }
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, apiTimeoutMillis());
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, apiTimeoutMillis());
        return new KafkaConsumer<>(props, new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    private KafkaProducer<byte[], byte[]> producer() {
        Map<String, Object> props = new HashMap<>(clientConfig);
        props.remove(ProducerConfig.TRANSACTIONAL_ID_CONFIG);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, apiTimeoutMillis());
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, apiTimeoutMillis());
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, apiTimeoutMillis());
        return new KafkaProducer<>(props, new ByteArraySerializer(), new ByteArraySerializer());
    }

    /** One broker request, as opposed to {@link #timeout}, which bounds a whole call. */
    private int apiTimeoutMillis() {
        return (int) Math.min(timeout.toMillis(), MAX_API_TIMEOUT_MILLIS);
    }

    private static RecordMetadata await(Future<RecordMetadata> send) {
        try {
            return send.get();
        } catch (InterruptedException e) {
            throw new InterruptException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof KafkaException kafka
                    ? kafka : new KafkaException("The broker refused the replay", e.getCause());
        }
    }

    private static List<TopicPartition> partitions(KafkaConsumer<byte[], byte[]> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null || infos.isEmpty()) {
            throw new UnknownTopicOrPartitionException("No topic " + topic);
        }
        return infos.stream().map(p -> new TopicPartition(topic, p.partition())).toList();
    }

    private static ReplayResult result(ConsumerRecord<byte[], byte[]> record, Outcome outcome, String detail) {
        return new ReplayResult(record.topic(), record.partition(), record.offset(),
                Instant.ofEpochMilli(record.timestamp()), outcome, detail);
    }

    private static String coordinates(ConsumerRecord<byte[], byte[]> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
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
}
