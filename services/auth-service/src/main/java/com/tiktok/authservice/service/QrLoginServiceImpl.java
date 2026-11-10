package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.QrLoginCreatedResponse;
import com.tiktok.authservice.dto.response.QrLoginScanResponse;
import com.tiktok.authservice.dto.response.QrLoginStatus;
import com.tiktok.authservice.dto.response.QrLoginStatusResponse;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.QrLoginAlreadyScannedException;
import com.tiktok.authservice.exception.QrLoginExpiredException;
import com.tiktok.authservice.exception.QrLoginNotAllowedException;
import com.tiktok.authservice.exception.TooManyQrLoginRequestsException;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrLoginServiceImpl implements QrLoginService {

    /** How long an unscanned code lives. Long enough to find the phone, short enough to go stale. */
    static final Duration CODE_TTL = Duration.ofMinutes(2);

    /** Whatever is left after a scan, the owner gets at least this long to confirm. */
    static final Duration CONFIRM_WINDOW = Duration.ofMinutes(1);

    /** Codes one address may ask for per window. A browser refreshing an expired code is a few. */
    static final int CREATE_LIMIT = 30;
    static final Duration CREATE_WINDOW = Duration.ofMinutes(10);

    /** The text the image carries. The app's scanner accepts only this scheme. */
    static final String QR_CONTENT_PREFIX = "nowa://qr-login?id=";

    /** Ids this service mints are 16 random bytes in base64url; anything else is not one of ours. */
    private static final Pattern QR_ID = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private static final String RATE_KEY_PREFIX = "auth:qr-create:";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

    private final QrLoginStore store;
    private final RequestOrigins requestOrigins;
    private final UserRepository userRepository;
    private final TokenIssuer tokenIssuer;
    private final StringRedisTemplate redisTemplate;

    @Override
    public QrLoginCreatedResponse create() {
        RequestOrigin origin = requestOrigins.current();
        checkCreateLimit(origin.address());

        String qrId = randomToken(16);
        String pollToken = randomToken(32);
        // The browser's label and place are fixed now, from the browser's own request: by the time
        // the phone asks, the request in hand is the phone's.
        store.create(qrId, HashUtils.sha256(pollToken), origin.alertLabel(), origin.location(), CODE_TTL);
        return new QrLoginCreatedResponse(qrId, pollToken, QR_CONTENT_PREFIX + qrId,
                Instant.now().plus(CODE_TTL));
    }

    @Override
    public QrLoginStatusResponse poll(String qrId, String pollToken) {
        if (!isOurId(qrId)) {
            return QrLoginStatusResponse.of(QrLoginStatus.EXPIRED);
        }
        QrLoginStore.PolledCode code = store.poll(qrId, HashUtils.sha256(pollToken));
        QrLoginStatus status = QrLoginStatus.valueOf(code.status());
        return switch (status) {
            case PENDING, REJECTED, EXPIRED -> QrLoginStatusResponse.of(status);
            case SCANNED -> new QrLoginStatusResponse(status, code.username(), null);
            case APPROVED -> issue(code);
        };
    }

    /**
     * The code is already spent when this runs, so whatever happens here it cannot be redeemed
     * twice. The account is read again: it may have been banned, closed or deleted since the phone
     * approved, and an approval is not a reason to open a closed account.
     */
    private QrLoginStatusResponse issue(QrLoginStore.PolledCode code) {
        Optional<User> user = Optional.ofNullable(code.userId())
                .flatMap(userRepository::findById)
                .filter(QrLoginServiceImpl::mayUseQr);
        if (user.isEmpty()) {
            return QrLoginStatusResponse.of(QrLoginStatus.REJECTED);
        }
        return new QrLoginStatusResponse(QrLoginStatus.APPROVED, user.get().getUsername(),
                tokenIssuer.issue(user.get(), SecurityEventType.LOGIN_SUCCEEDED));
    }

    @Override
    public QrLoginScanResponse scan(String qrId, Long userId) {
        requireOurId(qrId);
        User user = userRepository.findById(userId).filter(QrLoginServiceImpl::mayUseQr)
                .orElseThrow(QrLoginNotAllowedException::new);

        switch (store.scan(qrId, userId, user.getUsername(), CONFIRM_WINDOW)) {
            case GONE -> throw new QrLoginExpiredException();
            case TAKEN -> throw new QrLoginAlreadyScannedException();
            case OK -> { }
        }
        QrLoginStore.ScannedCode code = store.describe(qrId);
        return new QrLoginScanResponse(code.deviceLabel(), code.location(),
                Instant.now().plusMillis(code.remainingMillis()));
    }

    @Override
    public void approve(String qrId, Long userId) {
        decide(qrId, userId, QrLoginStatus.APPROVED);
    }

    @Override
    public void reject(String qrId, Long userId) {
        decide(qrId, userId, QrLoginStatus.REJECTED);
    }

    private void decide(String qrId, Long userId, QrLoginStatus status) {
        requireOurId(qrId);
        // GONE and TAKEN (not this account's scan, or already decided the other way) both read as
        // expired: either way the phone can do nothing more with this code.
        if (store.decide(qrId, userId, status.name()) != QrLoginStore.Claim.OK) {
            throw new QrLoginExpiredException();
        }
    }

    /**
     * Open accounts only, and never an admin: the console is reached with a password, Turnstile
     * and a mailed code, and a phone approval is none of those — see {@link SocialSignInPolicy}.
     */
    private static boolean mayUseQr(User user) {
        return !user.isDeleted() && user.getStatus() == UserStatus.ACTIVE && user.getRole() != UserRole.ADMIN;
    }

    /**
     * Fails open when Redis is down, like the login limiter: the code itself lives in Redis, so
     * creating one fails a moment later anyway, with the real error.
     */
    private void checkCreateLimit(String address) {
        if (address == null) {
            return;
        }
        Long hits;
        try {
            hits = WindowCounter.increment(redisTemplate, RATE_KEY_PREFIX + address, CREATE_WINDOW);
        } catch (DataAccessException e) {
            log.warn("QR sign-in rate limit unavailable, letting the request through", e);
            return;
        }
        if (hits != null && hits > CREATE_LIMIT) {
            throw new TooManyQrLoginRequestsException();
        }
    }

    private static boolean isOurId(String qrId) {
        return qrId != null && QR_ID.matcher(qrId).matches();
    }

    private static void requireOurId(String qrId) {
        if (!isOurId(qrId)) {
            throw new QrLoginExpiredException();
        }
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL_SAFE.encodeToString(buffer);
    }
}
