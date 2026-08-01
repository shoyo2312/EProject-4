package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.InboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {

    /**
     * Atomically claims an eventId via INSERT ... ON CONFLICT DO NOTHING, so that two
     * concurrent deliveries of the same event (Kafka redelivery/rebalance) can never both
     * proceed to process it - the loser gets 0 affected rows instead of a unique constraint
     * violation bubbling out of the transaction.
     */
    @Modifying
    @Query(value = "insert into inbox_events (id, event_id, event_type, processed_at) " +
            "values (:id, :eventId, :eventType, :processedAt) on conflict (event_id) do nothing",
            nativeQuery = true)
    int tryClaim(@Param("id") Long id,
                 @Param("eventId") String eventId,
                 @Param("eventType") String eventType,
                 @Param("processedAt") Instant processedAt);
}
