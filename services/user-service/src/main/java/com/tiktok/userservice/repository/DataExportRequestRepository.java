package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.DataExportRequest;
import com.tiktok.userservice.entity.DataExportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Every status change is a conditional update whose row count is the answer: 0 means the row was
 * not in the state the caller expected — the owner canceled while the worker finished, or the
 * other way round — and the caller acts on that instead of overwriting the winner.
 */
public interface DataExportRequestRepository extends JpaRepository<DataExportRequest, Long> {

    /** The owner's list. Twenty is more than anyone keeps: each new request expires the last file. */
    List<DataExportRequest> findTop20ByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);

    Optional<DataExportRequest> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    // The admin console's summary. Erased accounts are left out: nothing about them is pending.

    long countByStatusAndDeletedAtIsNull(DataExportStatus status);

    long countByStatusAndDeletedAtIsNullAndCreatedAtBefore(DataExportStatus status, Instant before);

    Optional<DataExportRequest> findFirstByStatusAndDeletedAtIsNullOrderByCreatedAtAsc(DataExportStatus status);

    /** A READY row past its expiry the sweep has not reached yet is not counted. */
    long countByStatusAndDeletedAtIsNullAndExpiresAtAfter(DataExportStatus status, Instant now);

    /** {@code updated_at} is the moment it failed: nothing touches a FAILED row afterwards. */
    long countByStatusAndDeletedAtIsNullAndUpdatedAtAfter(DataExportStatus status, Instant since);

    List<DataExportRequest> findByStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(DataExportStatus status,
                                                                               Pageable pageable);

    @Query("select count(r) > 0 from DataExportRequest r where r.userId = :userId "
            + "and r.status = com.tiktok.userservice.entity.DataExportStatus.PROCESSING and r.deletedAt is null")
    boolean hasProcessing(@Param("userId") Long userId);

    /** A new request makes the previous file expire, as it does live. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.status = com.tiktok.userservice.entity.DataExportStatus.EXPIRED, "
            + "r.updatedAt = :now, r.version = r.version + 1 where r.userId = :userId "
            + "and r.status = com.tiktok.userservice.entity.DataExportStatus.READY and r.deletedAt is null")
    int expireReadyOf(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.status = com.tiktok.userservice.entity.DataExportStatus.CANCELED, "
            + "r.updatedAt = :now, r.version = r.version + 1 where r.id = :id and r.userId = :userId "
            + "and r.status = com.tiktok.userservice.entity.DataExportStatus.PROCESSING and r.deletedAt is null")
    int cancel(@Param("id") Long id, @Param("userId") Long userId, @Param("now") Instant now);

    /**
     * The oldest request no live worker holds, locked until the claiming transaction ends. SKIP
     * LOCKED so two instances polling at once take different rows instead of queueing on one.
     */
    @Query(value = "SELECT id FROM data_export_requests WHERE status = 'PROCESSING' AND deleted_at IS NULL "
            + "AND (claimed_at IS NULL OR claimed_at < :staleBefore) "
            + "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Long> findNextClaimable(@Param("staleBefore") Instant staleBefore);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.claimedAt = :now, r.attempts = r.attempts + 1, "
            + "r.updatedAt = :now, r.version = r.version + 1 where r.id = :id")
    int claim(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.status = com.tiktok.userservice.entity.DataExportStatus.READY, "
            + "r.objectKey = :objectKey, r.sizeBytes = :sizeBytes, r.readyAt = :readyAt, r.expiresAt = :expiresAt, "
            + "r.claimedAt = null, r.updatedAt = :readyAt, r.version = r.version + 1 where r.id = :id "
            + "and r.status = com.tiktok.userservice.entity.DataExportStatus.PROCESSING and r.deletedAt is null")
    int complete(@Param("id") Long id,
                 @Param("objectKey") String objectKey,
                 @Param("sizeBytes") long sizeBytes,
                 @Param("readyAt") Instant readyAt,
                 @Param("expiresAt") Instant expiresAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.status = com.tiktok.userservice.entity.DataExportStatus.FAILED, "
            + "r.claimedAt = null, r.updatedAt = :now, r.version = r.version + 1 where r.id = :id "
            + "and r.status = com.tiktok.userservice.entity.DataExportStatus.PROCESSING")
    int fail(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.status = com.tiktok.userservice.entity.DataExportStatus.EXPIRED, "
            + "r.updatedAt = :now, r.version = r.version + 1 where r.status = com.tiktok.userservice.entity.DataExportStatus.READY "
            + "and r.expiresAt <= :now")
    int expireDue(@Param("now") Instant now);

    /** Files nobody can download any more: a final status, or an account that is gone. */
    @Query("select r from DataExportRequest r where r.objectKey is not null "
            + "and (r.status <> com.tiktok.userservice.entity.DataExportStatus.READY or r.deletedAt is not null)")
    List<DataExportRequest> findFilesToRemove(Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.objectKey = null, r.version = r.version + 1 "
            + "where r.id = :id and r.objectKey = :objectKey")
    int clearObjectKey(@Param("id") Long id, @Param("objectKey") String objectKey);

    /** The account is being erased: its requests go, and one still processing is never built. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update DataExportRequest r set r.deletedAt = :at, r.status = case "
            + "when r.status = com.tiktok.userservice.entity.DataExportStatus.PROCESSING "
            + "then com.tiktok.userservice.entity.DataExportStatus.CANCELED else r.status end, "
            + "r.updatedAt = :at, r.version = r.version + 1 where r.userId = :userId and r.deletedAt is null")
    int softDeleteAllOf(@Param("userId") Long userId, @Param("at") Instant at);
}
