package com.tiktok.common.id;

/**
 * Twitter-style Snowflake ID generator: 41-bit timestamp, 10-bit node id, 12-bit sequence.
 * Node id comes from the SNOWFLAKE_NODE_ID env var (set per pod/instance); falls back to a
 * random value so a single local run still works, but multi-instance deployments must set it
 * explicitly to avoid collisions.
 */
public final class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z

    private static final long NODE_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_NODE_ID = ~(-1L << NODE_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private static final long NODE_ID = resolveNodeId();

    private static long lastTimestamp = -1L;
    private static long sequence = 0L;

    private SnowflakeIdGenerator() {
    }

    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards, refusing to generate id");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                while (timestamp <= lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << (NODE_ID_BITS + SEQUENCE_BITS))
                | (NODE_ID << SEQUENCE_BITS)
                | sequence;
    }

    private static long resolveNodeId() {
        String env = System.getenv("SNOWFLAKE_NODE_ID");
        if (env != null) {
            try {
                return Long.parseLong(env) & MAX_NODE_ID;
            } catch (NumberFormatException ignored) {
                // fall through to random node id
            }
        }
        return (long) (Math.random() * (MAX_NODE_ID + 1));
    }
}
