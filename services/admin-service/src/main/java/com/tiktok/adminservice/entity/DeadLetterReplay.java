package com.tiktok.adminservice.entity;

import com.tiktok.common.entity.BaseEntity;
import com.tiktok.kafka.dlt.ReplayResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/** One DLT record an admin replayed from the console, and what became of it. Append-only; see V8. */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "dead_letter_replays")
public class DeadLetterReplay extends BaseEntity {

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "dlt_topic", nullable = false)
    private String dltTopic;

    @Column(name = "dlt_partition", nullable = false)
    private int dltPartition;

    @Column(name = "dlt_offset", nullable = false)
    private long dltOffset;

    @Column(name = "parked_at", nullable = false)
    private Instant parkedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private ReplayResult.Outcome outcome;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "forced", nullable = false)
    private boolean forced;
}
