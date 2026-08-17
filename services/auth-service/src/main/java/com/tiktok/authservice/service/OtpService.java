package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.VerificationToken;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import com.tiktok.crypto.hash.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Function;

/**
 * Issuing and spending the six-digit codes, for every flow that uses one: email verification,
 * password reset, and the social-link challenge.
 *
 * <p>Extracted from {@code AuthServiceImpl} when the third caller arrived. The rules encoded here
 * — the hash covers user+type+otp, a reissue deletes the previous code, a wrong guess is counted
 * before it is answered — are what stop a 1e6-value secret from being brute-forced, and a second
 * copy of them would be a second place for one of those rules to go missing.
 *
 * <p>No transaction of its own: every method takes part in the caller's, so an OTP is written
 * exactly when the change it authorises is.
 */
@Component
@RequiredArgsConstructor
public class OtpService {

    private final UserRepository userRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final OtpGenerator otpGenerator;
    private final OtpRateLimiter otpRateLimiter;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Replaces whatever code the user last held for this type and publishes the event that mails
     * the new one.
     *
     * <p>Flushed before the insert rather than at the end of the transaction: a derived delete is
     * queued as em.remove(), and Hibernate orders inserts ahead of deletes at flush time.
     * token_hash is UNIQUE and folds in the OTP, so a resend that happens to draw the same digits
     * for the same user and type would collide with the row this delete is about to remove and
     * fail the request with a 500. Rare — 1e-6 per resend — and unexplainable when it happens.
     */
    public void issue(User user, VerificationTokenType type, long expiryMillis,
                      Function<String, ?> eventFactory) {
        verificationTokenRepository.deleteAllByUserIdAndTokenType(user.getId(), type);
        verificationTokenRepository.flush();

        String otp = otpGenerator.generate();
        VerificationToken token = VerificationToken.builder()
                .userId(user.getId())
                .tokenHash(hash(user.getId(), type, otp))
                .tokenType(type)
                .expiresAt(Instant.now().plusMillis(expiryMillis))
                .build();
        verificationTokenRepository.save(token);

        eventPublisher.publishEvent(eventFactory.apply(otp));
    }

    /**
     * Spends a code and answers with the account it belonged to. A wrong code and an expired one
     * are both {@link InvalidOtpException} — which of the two it was is not the caller's business,
     * and saying would tell an attacker when to keep guessing.
     */
    public User consume(String purpose, VerificationTokenType type, String email, String otp) {
        otpRateLimiter.checkGuessAllowed(purpose, email);

        try {
            User user = userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email)
                    .orElseThrow(InvalidOtpException::new);

            VerificationToken token = verificationTokenRepository
                    .findByTokenHashAndTokenType(hash(user.getId(), type, otp), type)
                    .filter(VerificationToken::isValid)
                    .orElseThrow(InvalidOtpException::new);

            token.markUsed();
            verificationTokenRepository.save(token);

            otpRateLimiter.recordGuessSuccess(purpose, email);
            return user;
        } catch (InvalidOtpException e) {
            otpRateLimiter.recordGuessFailure(purpose, email);
            throw e;
        }
    }

    /**
     * The stored form. Salted with the user id and the type so the same digits cannot be replayed
     * against another account, or against the same account's other flow.
     */
    private String hash(Long userId, VerificationTokenType type, String otp) {
        return HashUtils.sha256(userId + ":" + type + ":" + otp);
    }
}
