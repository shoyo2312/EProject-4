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

    /**
     * Atomically retires a token because /refresh is about to issue its successor, returning 1 if
     * this caller is the one that retired it and 0 if somebody else already had.
     *
     * <p>The 0 is the whole point. Reading the row, checking {@code rotatedAt == null} and then
     * writing it is check-then-act: two concurrent refreshes of the same token both read null,
     * both rotate, and two live chains walk away — which is precisely the attacker-plus-victim
     * situation replay detection exists to catch, so the detection was blind to its own scenario.
     * Here the loser's UPDATE matches no row, because {@code rotated_at IS NULL} stopped being
     * true, and the caller learns it lost. Under READ COMMITTED the second transaction blocks on
     * the winner's row lock until it commits, so the outcome does not depend on timing.
     *
     * <p>{@code revoked_at IS NULL} is deliberately NOT part of the predicate: logout revokes
     * without rotating, and treating a logged-out token as a lost race would turn signing out on
     * one device into signing out everywhere. Whether the token is still active is checked
     * separately, on the row that was read.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.rotatedAt = :now, t.revokedAt = :now " +
            "WHERE t.tokenHash = :tokenHash AND t.rotatedAt IS NULL")
    int claimForRotation(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

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
