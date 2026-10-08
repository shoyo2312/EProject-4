package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OtpProperties;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.RememberedDevice;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.event.local.AdminLoginOtpRequestedEvent;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.repository.RememberedDeviceRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The second factor on admin-console login: an emailed OTP, unless this browser is already a
 * remembered device.
 *
 * <p>Its own bean, not inline in {@link AuthServiceImpl#login}, because both write paths here need
 * a transaction and {@code login} deliberately runs without one (see that method). Called from
 * {@code login}, the {@code @Transactional} methods below go through the proxy and get theirs.
 */
@Component
@RequiredArgsConstructor
public class AdminMfaService {

    private static final String OTP_PURPOSE = "admin-login";
    /** ponytail: a fixed trust window — 30 days is a policy choice, not an env knob. */
    private static final long REMEMBER_DEVICE_MILLIS = Duration.ofDays(30).toMillis();
    private static final int DEVICE_TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final RememberedDeviceRepository rememberedDeviceRepository;
    private final OtpService otpService;
    private final OtpRateLimiter otpRateLimiter;
    private final OtpProperties otpProperties;
    private final TokenIssuer tokenIssuer;
    private final SecurityEventRecorder securityEventRecorder;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Whether this device token still lets {@code user} skip the OTP step. Bound to one account:
     * a cookie lifted from another admin's browser matches a row but not this {@code userId}, and
     * is refused.
     */
    public boolean isTrustedDevice(User user, String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return false;
        }
        return rememberedDeviceRepository
                .findByTokenHashAndExpiresAtAfter(HashUtils.sha256(deviceToken), Instant.now())
                .filter(row -> row.getUserId().equals(user.getId()))
                .isPresent();
    }

    /** Mails a fresh admin-login code for {@code user}, throttled like every other OTP send. */
    @Transactional
    public void issueLoginChallenge(User user) {
        otpRateLimiter.checkAllowed(OTP_PURPOSE, user.getEmail());
        otpService.issue(user, VerificationTokenType.ADMIN_LOGIN, otpProperties.adminLoginExpiryMillis(),
                otp -> new AdminLoginOtpRequestedEvent(user.getEmail(), otp));

        securityEventRecorder.record(user.getId(), SecurityEventType.MFA_CHALLENGED);
    }

    /**
     * Spends the code and issues the session. {@code usernameOrEmail} travelled back through the
     * client, so the account is re-resolved and re-checked for ADMIN here rather than trusted.
     * A bad or expired code is {@link InvalidOtpException}, same as everywhere else.
     *
     * @return the tokens, with {@code deviceToken} populated when {@code rememberDevice} is set.
     */
    @Transactional
    public TokenResponse completeLogin(String usernameOrEmail, String otp, boolean rememberDevice) {
        User user = userRepository.findSignInAccount(usernameOrEmail)
                .orElseThrow(InvalidOtpException::new);

        if (user.getRole() != UserRole.ADMIN || user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidOtpException();
        }

        otpService.consume(OTP_PURPOSE, VerificationTokenType.ADMIN_LOGIN, user.getEmail(), otp);

        TokenResponse tokens = tokenIssuer.issue(user, SecurityEventType.LOGIN_SUCCEEDED);
        if (!rememberDevice) {
            return tokens;
        }

        String deviceToken = randomToken();
        rememberedDeviceRepository.save(RememberedDevice.builder()
                .userId(user.getId())
                .tokenHash(HashUtils.sha256(deviceToken))
                .expiresAt(Instant.now().plusMillis(REMEMBER_DEVICE_MILLIS))
                .build());

        // Worth its own entry: from here until it expires, this browser signs in on the password
        // alone. That is the account's second factor being switched off for one device, which is
        // exactly the change an admin who never made it needs to be able to find.
        securityEventRecorder.record(user.getId(), SecurityEventType.DEVICE_REMEMBERED,
                "admin second factor skipped on this browser for 30 days");

        return new TokenResponse(tokens.accessToken(), tokens.refreshToken(),
                tokens.expiresInMillis(), deviceToken);
    }

    private String randomToken() {
        byte[] bytes = new byte[DEVICE_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
