package com.tiktok.recommendationservice.service;

/**
 * Deduplicates Kafka event delivery. This service holds no durable store of its own
 * (Redis is the sole datastore), so "inbox" is a Redis key with a TTL rather than a table.
 */
public interface InboxService {

    /**
     * Returns true and marks the event as seen if this is the first time {@code eventId}
     * is presented; returns false without side effects if it was already seen.
     */
    boolean markIfNew(String eventId);

    /**
     * Runs {@code work} at most once per {@code eventId}, releasing the claim if it throws.
     *
     * <p>The release is the whole point, and a claim without one is worse than no claim at all.
     * Every consumer here takes the claim before doing the work — it has to, or two deliveries
     * during a rebalance both pass the check and both fold the same watch into the counters. But
     * a claim taken and never released turns kafka-lib's retry into a silent drop: the work
     * throws (Redis blipped), the error handler redelivers, this call sees the event as already
     * processed, returns having done nothing, and the offset commits. The event is gone, and
     * nothing reaches the DLT to say so.
     *
     * <p>Mirrors video-service's {@code IdempotentEventProcessor}, which solves the same problem
     * against Mongo. A hard crash between the work and the release can still drop an event; there
     * is no transaction spanning Redis and the work, and this is the smaller of the two risks.
     */
    void runOnce(String eventId, Runnable work);
}
