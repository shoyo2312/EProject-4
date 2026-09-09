package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * All lookups are case-insensitive: an account registered as Test@Gmail.com must be reachable by
 * typing test@gmail.com, and two usernames differing only in case must not coexist. The matching
 * unique indexes are on lower(email)/lower(username) — see V6 — so these stay index-backed.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    Optional<User> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    boolean existsByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    /**
     * Admin console directory. Both filters are optional and independent, which is four
     * combinations — one query with null-tolerant predicates rather than four derived methods.
     * {@code term} is expected already lowercased and wrapped in wildcards; a null email simply
     * fails the LIKE, which is the wanted behaviour for a social account that has none.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND (:status IS NULL OR u.status = :status)
              AND (:term IS NULL
                   OR LOWER(u.username) LIKE :term
                   OR LOWER(u.email) LIKE :term)
            """)
    Page<User> searchForAdmin(@Param("status") UserStatus status,
                              @Param("term") String term,
                              Pageable pageable);
}
