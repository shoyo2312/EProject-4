package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.KnownDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * {@code known_devices} and its per-account marker {@code known_device_accounts}. Native queries
 * throughout: "is this device new" and "is this the account's first device" are answered by the
 * row count of an {@code INSERT … ON CONFLICT DO NOTHING}, not by a read followed by a write —
 * two simultaneous sign-ins from one new browser would both read "new" and both alert.
 */
public interface KnownDeviceRepository extends JpaRepository<KnownDevice, Long> {

    /** 1 when the device is new to the account, 0 when it was already known. */
    @Modifying
    @Query(value = "INSERT INTO known_devices (id, user_id, device_hash, last_seen_at) "
            + "VALUES (:id, :userId, :deviceHash, :now) ON CONFLICT (user_id, device_hash) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") Long id, @Param("userId") Long userId,
                       @Param("deviceHash") String deviceHash, @Param("now") Instant now);

    @Modifying
    @Query(value = "UPDATE known_devices SET last_seen_at = :now "
            + "WHERE user_id = :userId AND device_hash = :deviceHash", nativeQuery = true)
    int touch(@Param("userId") Long userId, @Param("deviceHash") String deviceHash, @Param("now") Instant now);

    /** 1 when this is the first device the account has ever had, 0 when it has had one before. */
    @Modifying
    @Query(value = "INSERT INTO known_device_accounts (user_id) VALUES (:userId) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int markAccount(@Param("userId") Long userId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM known_device_accounts WHERE user_id = :userId)",
            nativeQuery = true)
    boolean accountHasHadDevice(@Param("userId") Long userId);

    /** Deletes the account's devices beyond the {@code keep} most recently used. */
    @Modifying
    @Query(value = "DELETE FROM known_devices WHERE id IN ("
            + "SELECT id FROM known_devices WHERE user_id = :userId "
            + "ORDER BY last_seen_at DESC, id DESC OFFSET :keep)", nativeQuery = true)
    int evictBeyond(@Param("userId") Long userId, @Param("keep") int keep);

    /**
     * Deletes up to {@code batchSize} devices unused since {@code cutoff}. Same batched-native shape
     * as {@link AuthSecurityEventRepository#deleteRecordedBefore}, driven by
     * {@code ExpiredRecordCleanup}. The account's row in {@code known_device_accounts} stays.
     */
    @Modifying
    @Query(value = "DELETE FROM known_devices WHERE id IN ("
            + "SELECT id FROM known_devices WHERE last_seen_at < :cutoff ORDER BY last_seen_at LIMIT :batchSize)",
            nativeQuery = true)
    int deleteUnusedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = "DELETE FROM known_devices WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM known_device_accounts WHERE user_id = :userId", nativeQuery = true)
    int unmarkAccount(@Param("userId") Long userId);
}
