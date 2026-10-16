package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.AuthSecurityEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuthSecurityEventRepository extends JpaRepository<AuthSecurityEvent, Long> {

    /**
     * One account's log, newest first — the only way the console reads it, and the order
     * idx_auth_security_events_user already stores, so the page is a range scan.
     */
    Page<AuthSecurityEvent> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Deletes up to {@code batchSize} entries older than the cutoff. Same batched-native shape as
     * {@link RefreshTokenRepository#deleteExpiredBefore}, driven by {@code ExpiredRecordCleanup}.
     *
     * <p>Age is the only condition there can be: unlike a token, an entry never stops being true.
     * It stops being worth keeping, which is a retention policy rather than a fact about the row,
     * so the grace is set deliberately long — this is the trail an investigation reads.
     */
    @Modifying
    @Query(value = "DELETE FROM auth_security_events WHERE id IN (" +
            "SELECT id FROM auth_security_events WHERE created_at < :cutoff ORDER BY created_at LIMIT :batchSize)",
            nativeQuery = true)
    int deleteRecordedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
