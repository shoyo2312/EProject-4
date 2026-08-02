package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.VerificationToken;
import com.tiktok.authservice.entity.VerificationTokenType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {

    Optional<VerificationToken> findByTokenHashAndTokenType(String tokenHash, VerificationTokenType tokenType);

    void deleteAllByUserIdAndTokenType(Long userId, VerificationTokenType tokenType);
}
