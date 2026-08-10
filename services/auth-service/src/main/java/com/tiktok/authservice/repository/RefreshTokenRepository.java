package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :revokedAt WHERE t.userId = :userId AND t.revokedAt IS NULL")
    void revokeAllByUserId(Long userId, Instant revokedAt);

    /**
     * Deletes up to {@code batchSize} rows whose expiry is already behind {@code cutoff},
     * returning how many went.
     *
     * <p>Expiry, not revocation, is the condition: a revoked row still answers "this token was
     * rotated, and here is when", which is what replay detection reads. Once the token cannot be
     * presented anyway that answer stops mattering. Deleting a live row would silently turn a
     * replay into a plain unknown token.
     *
     * <p>The LIMIT lives in a subquery because Postgres does not accept LIMIT on DELETE, and it
     * is there so a table nobody has ever cleaned is emptied over many short transactions rather
     * than one that locks a million rows at once.
     */
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE id IN (" +
            "SELECT id FROM refresh_tokens WHERE expires_at < :cutoff ORDER BY expires_at LIMIT :batchSize)",
            nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
