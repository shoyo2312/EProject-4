package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    /** For the backlog gauges (OutboxMetricsConfig); both read idx_outbox_events_unpublished. */
    long countByPublishedAtIsNull();

    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.publishedAt IS NULL")
    Instant oldestPendingCreatedAt();

    /** The rows the broker acknowledged, marked in one statement and its own transaction. */
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.publishedAt = :publishedAt WHERE e.id IN :ids")
    int markPublished(@Param("ids") List<Long> ids, @Param("publishedAt") Instant publishedAt);

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
