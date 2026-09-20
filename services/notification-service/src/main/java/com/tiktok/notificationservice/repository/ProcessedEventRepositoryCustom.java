package com.tiktok.notificationservice.repository;

public interface ProcessedEventRepositoryCustom {

    /**
     * Atomically claims an eventId, mirroring user-service's
     * {@code InboxEventRepository.tryClaim} (INSERT ... ON CONFLICT DO NOTHING) with the
     * equivalent Mongo primitive: an insert guarded by the unique index on eventId.
     *
     * <p>Returns false if the event was already claimed. Requires
     * {@code spring.data.mongodb.auto-index-creation} to be on — without the unique index this
     * silently degrades to "always claims", which here means a user gets the same notification
     * once per redelivery.
     */
    boolean tryClaim(String eventId, String eventType);

    /**
     * Releases a claim so a failed delivery can be retried. See
     * {@link com.tiktok.notificationservice.event.consumer.IdempotentEventProcessor} for why the
     * claim is taken before the work rather than after it.
     */
    void releaseClaim(String eventId);
}
