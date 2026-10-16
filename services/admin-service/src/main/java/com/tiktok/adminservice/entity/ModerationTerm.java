package com.tiktok.adminservice.entity;

import com.tiktok.common.entity.BaseEntity;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.rules.AdminTerm;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * One term an admin added to the text moderation list, or took out of it, without a deploy. The
 * services read the live ones off {@code /api/v1/admin/internal/blocklist}; see V7.
 *
 * <p>Soft-deleted like everything else, which is also the history: who added a term, who took it
 * out, and when, stay on the row.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "moderation_terms")
public class ModerationTerm extends BaseEntity {

    @Column(name = "term", nullable = false, length = 100)
    private String term;

    /** {@code Blocklist.keyOf(term)} — unique among live rows. */
    @Column(name = "term_key", nullable = false, length = 200)
    private String termKey;

    /** Null for EXEMPT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10)
    private AdminTerm.Action action;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "deleted_by")
    private Long deletedBy;

    public void deleteBy(Long adminId) {
        markDeleted();
        this.deletedBy = adminId;
    }
}
