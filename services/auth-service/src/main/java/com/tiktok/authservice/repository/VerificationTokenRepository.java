package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.VerificationToken;
import com.tiktok.authservice.entity.VerificationTokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    Optional<VerificationToken> findByTokenHashAndTokenType(String tokenHash, VerificationTokenType tokenType);

    void deleteAllByUserIdAndTokenType(Long userId, VerificationTokenType tokenType);

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
