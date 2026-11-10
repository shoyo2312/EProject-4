package com.tiktok.kafka.dlt;

import org.apache.kafka.clients.CommonClientConfigs;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

/**
 * {@link DeadLetters} from a terminal:
 * <pre>
 * make dlt-list   TOPIC=video.video-events.DLT
 * make dlt-replay TOPIC=video.video-events.DLT OFFSET=0:42   one record, DLT partition:offset as listed
 * make dlt-replay TOPIC=video.video-events.DLT ALL=1         every record the dlt-replay group has not been past
 * </pre>
 * {@code ALL=1} moves past what it holds back, and says how to replay each one on its own. Plain
 * bootstrap servers only, no SASL: on production the admin console does the same through
 * admin-service, whose Kafka client is configured with the password.
 */
public final class DltReplay {

    /** Generous: a hand-run replay of a whole DLT reads each record's original partition. */
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private final DeadLetters deadLetters;
    private final PrintStream out;

    DltReplay(String bootstrapServers, PrintStream out) {
        this.deadLetters = new DeadLetters(
                Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers), TIMEOUT);
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

    /** "done" means an ALL=1 run has been past it — replayed, or held back and reported. */
    void list(String dltTopic) {
        int[] count = {0};
        deadLetters.forEach(dltTopic, letter -> {
            count[0]++;
            out.printf("%d:%d  %s  key=%s  eventType=%s  group=%s  from %s%n",
                    letter.partition(), letter.offset(), letter.pending() ? "pending" : "done   ",
                    letter.key(), letter.eventType(), letter.consumerGroup(), letter.origin());
            out.printf("      %s%n", letter.error());
        });
        out.println(count[0] + " record(s) on " + dltTopic);
    }

    void replay(String dltTopic, int partition, long offset, boolean force) {
        print(deadLetters.replay(dltTopic, partition, offset, force));
    }

    void replayAll(String dltTopic, boolean force) {
        int count = deadLetters.replayPending(dltTopic, force, Integer.MAX_VALUE, this::print);
        out.println(count + " record(s) on " + dltTopic + " since the last ALL=1 run");
        long left = deadLetters.topic(dltTopic).pending();
        if (left > 0) {
            out.println(left + " still pending — the run hit its time limit or more were parked meanwhile; run it again");
        }
    }

    private void print(ReplayResult result) {
        String self = result.coordinates();
        switch (result.outcome()) {
            case REPLAYED -> out.println("replayed " + self + " -> " + result.detail());
            case HELD_BACK -> out.println("skipped " + self + ": " + result.detail()
                    + ". If replaying it after that is right: OFFSET=" + result.partition() + ":" + result.offset()
                    + " FORCE=1");
            case ALREADY_REPLAYED -> out.println("skipped " + self + ": already replayed as " + result.detail());
            case NOT_PARKED -> out.println("skipped " + self
                    + ": no kafka_dlt-original-* headers, so not a record kafka-lib parked");
            case NOT_FOUND -> out.println("no record at " + self);
        }
    }
}
