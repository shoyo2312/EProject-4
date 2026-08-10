package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    /**
     * Deletes up to {@code batchSize} rows the broker acknowledged before {@code cutoff}.
     *
     * <p>{@code published_at IS NOT NULL} is not a nicety: an unpublished row is an event that
     * has not reached Kafka yet, and the outbox is the only place it exists. Deleting one on age
     * would lose it permanently — the exact failure the pattern is there to prevent — and a row
     * sitting unpublished for weeks is a broken publisher to go and fix, not garbage to sweep up.
     */
    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE id IN (" +
            "SELECT id FROM outbox_events WHERE published_at IS NOT NULL AND published_at < :cutoff " +
            "ORDER BY published_at LIMIT :batchSize)",
            nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
