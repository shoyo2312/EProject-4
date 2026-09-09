package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.RememberedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RememberedDeviceRepository extends JpaRepository<RememberedDevice, Long> {

    /**
     * A still-valid trusted-device row for this exact token hash. The caller checks the row's
     * {@code userId} matches the account that just passed its password — a device token is bound
     * to one admin, and a stolen cookie must not skip OTP for a different login.
     */
    Optional<RememberedDevice> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

    /**
     * Deletes up to {@code batchSize} rows already past their expiry. Same batched-native shape as
     * {@link VerificationTokenRepository#deleteExpiredBefore}, driven by {@code ExpiredRecordCleanup}.
     */
    @Modifying
    @Query(value = "DELETE FROM remembered_devices WHERE id IN (" +
            "SELECT id FROM remembered_devices WHERE expires_at < :cutoff ORDER BY expires_at LIMIT :batchSize)",
            nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
