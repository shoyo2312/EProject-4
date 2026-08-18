package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.VerificationToken;
import com.tiktok.authservice.entity.VerificationTokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    void deleteAllByUserIdAndTokenType(Long userId, VerificationTokenType tokenType);

    /**
     * Atomically spends an OTP, returning 1 if this caller is the one that spent it and 0 if the
     * code is unknown, expired, or somebody else got there first.
     *
     * <p>The 0 is the point, same shape as {@link RefreshTokenRepository#claimForRotation}: reading
     * the row, checking {@code isValid()} and then writing {@code usedAt} is check-then-act, so two
     * concurrent submissions of the same code both read it unused and both spend it. Here the loser
     * matches no row because {@code used_at IS NULL} stopped being true, and is answered with the
     * same InvalidOtpException a wrong code gets.
     *
     * <p>Expiry is part of the predicate rather than a separate read for the same reason — the row
     * must be judged and written in one statement, or the judgement can go stale in between.
     *
     * <p>No {@code clearAutomatically}: nothing reads the token row after it is spent, and clearing
     * would detach the {@code User} the caller is about to mark verified or give a new password to.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE VerificationToken t SET t.usedAt = :now " +
            "WHERE t.tokenHash = :tokenHash AND t.tokenType = :tokenType " +
            "AND t.usedAt IS NULL AND t.expiresAt > :now")
    int claimForUse(@Param("tokenHash") String tokenHash,
                    @Param("tokenType") VerificationTokenType tokenType,
                    @Param("now") Instant now);

    /**
     * Deletes up to {@code batchSize} OTP rows whose expiry is already behind {@code cutoff}.
     *
     * <p>Expiry alone, without looking at {@code used_at}: an OTP lives fifteen minutes, so a
     * used one is unusable within the hour either way, and treating "used" as its own condition
     * would only delete the rows that a support question is most likely to be about.
     */
    @Modifying
    @Query(value = "DELETE FROM verification_tokens WHERE id IN (" +
            "SELECT id FROM verification_tokens WHERE expires_at < :cutoff ORDER BY expires_at LIMIT :batchSize)",
            nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
