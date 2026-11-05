package com.tiktok.adminservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reports")
public class Report extends BaseEntity {

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private String targetId;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private ReportSource source = ReportSource.PROFILE;

    @Column(name = "conversation_id")
    private String conversationId;

    @Column(name = "request_id")
    private String requestId;

    @Builder.Default
    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReportStatus status;

    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public void resolve(Long adminId, ReportStatus finalStatus) {
        this.status = finalStatus;
        this.resolvedBy = adminId;
        this.resolvedAt = Instant.now();
    }
}
