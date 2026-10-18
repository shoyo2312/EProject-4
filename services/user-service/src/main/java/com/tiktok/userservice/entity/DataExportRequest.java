package com.tiktok.userservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * One "Download your data" request — see V17 for the lifecycle. Every transition is a conditional
 * update in {@link com.tiktok.userservice.repository.DataExportRequestRepository}, not a setter:
 * the worker and the owner act on the same row from different threads, and the loser of a race
 * (a cancel against a finishing build) must see that it lost.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "data_export_requests")
public class DataExportRequest extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 8)
    private DataExportFormat format;

    /** Stored as the enum names, in the order {@link DataExportCategory} declares them. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "categories", nullable = false, columnDefinition = "text[]")
    private List<String> categories;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DataExportStatus status;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    public List<DataExportCategory> categoryList() {
        return categories.stream().map(DataExportCategory::valueOf).toList();
    }

    /** READY and not yet past {@code expiresAt} — the sweep that marks it EXPIRED runs only now and then. */
    public boolean isDownloadable(Instant now) {
        return status == DataExportStatus.READY && objectKey != null && expiresAt != null && now.isBefore(expiresAt);
    }
}
