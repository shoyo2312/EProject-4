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
}
