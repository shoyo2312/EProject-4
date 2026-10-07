package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * All lookups are case-insensitive: an account registered as Test@Gmail.com must be reachable by
 * typing test@gmail.com, and two usernames differing only in case must not coexist. The matching
 * unique indexes are on lower(email)/lower(username) — see V6.
 *
 * <p>The four identity lookups spell out {@code lower(...)} instead of leaving it to the
 * {@code IgnoreCase} in their names: Spring Data derives that as {@code upper(col) = upper(?)},
 * which no index here matches, so every sign-in, registration check and reset request was a
 * sequential scan of the whole table. The names stay so no caller changes.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Bans that have run out, for the sweep that lifts them. */
    List<User> findByStatusAndBannedUntilBefore(UserStatus status, Instant cutoff);

    @Query("select u from User u where lower(u.username) = lower(:username) and u.deletedAt is null")
    Optional<User> findByUsernameIgnoreCaseAndDeletedAtIsNull(@Param("username") String username);

    @Query("select u from User u where lower(u.email) = lower(:email) and u.deletedAt is null")
    Optional<User> findByEmailIgnoreCaseAndDeletedAtIsNull(@Param("email") String email);

    @Query("select count(u) > 0 from User u where lower(u.username) = lower(:username) and u.deletedAt is null")
    boolean existsByUsernameIgnoreCaseAndDeletedAtIsNull(@Param("username") String username);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email) and u.deletedAt is null")
    boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(@Param("email") String email);

    /**
     * Admin console directory. Both filters are optional and independent, which is four
     * combinations — one query with null-tolerant predicates rather than four derived methods.
     * {@code term} is expected already lowercased, LIKE-escaped with a backslash and wrapped in
     * wildcards; a null email simply fails the LIKE, which is the wanted behaviour for a social
     * account that has none.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND (:status IS NULL OR u.status = :status)
              AND (:term IS NULL
                   OR LOWER(u.username) LIKE :term ESCAPE '\\'
                   OR LOWER(u.email) LIKE :term ESCAPE '\\')
            """)
    Page<User> searchForAdmin(@Param("status") UserStatus status,
                              @Param("term") String term,
                              Pageable pageable);

    /**
     * Stamps {@code last_login_at} without touching {@code version}: a JPQL bulk update is not
     * versioned unless it says so. Saving the entity instead bumped the version on every sign-in
     * and refresh, so two devices of one account refreshing together raced for the row and the
     * slower one answered 409 — to a refresh that had already rotated its token successfully.
     * Nothing reads this field to decide anything, so last-writer-wins is the right lock.
     */
    @Modifying(flushAutomatically = true)
    @Query("update User u set u.lastLoginAt = :at where u.id = :userId")
    int stampLastLogin(@Param("userId") Long userId, @Param("at") Instant at);
}
