package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.ModerationTerm;
import com.tiktok.moderation.rules.AdminTerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ModerationTermRepository extends JpaRepository<ModerationTerm, Long> {

    Optional<ModerationTerm> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByTermKeyAndDeletedAtIsNull(String termKey);

    /** Every live term — the whole list, which is what a service's matcher is rebuilt from. */
    List<ModerationTerm> findByDeletedAtIsNullOrderByIdAsc();

    long countByDeletedAtIsNull();

    /**
     * The console's table: live terms, optionally one action, optionally containing {@code q}.
     * Ordered by the pageable, which the service fixes to newest first.
     */
    @Query("""
            SELECT t FROM ModerationTerm t
            WHERE t.deletedAt IS NULL
              AND (:action IS NULL OR t.action = :action)
              AND (:q IS NULL OR LOWER(t.term) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
            """)
    Page<ModerationTerm> search(@Param("q") String q, @Param("action") AdminTerm.Action action, Pageable pageable);
}
