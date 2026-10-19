package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.AuthSecurityEvent;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import com.tiktok.common.http.InternalApiToken;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Appends to an account's security log: what happened, from which address and which browser.
 *
 * <p>Two things about how this is written are load-bearing.
 *
 * <p><b>Its own transaction.</b> The entries most worth having are the ones written on a path that
 * then throws — a failed sign-in, a rejected token replay. Joining the caller's transaction would
 * roll back exactly those, leaving a log that holds every successful login and no failed one,
 * which is the opposite of what it is for.
 *
 * <p><b>It never throws.</b> A log that can fail a sign-in is a new way to lock every user out of
 * the platform, so a write that fails is logged and swallowed. That is also why the transaction is
 * driven by a {@link TransactionTemplate} rather than {@code @Transactional}: a catch inside an
 * annotated method still leaves the transaction marked rollback-only, and the caller gets an
 * {@code UnexpectedRollbackException} from the proxy that the catch cannot see. Here the commit
 * happens inside the try, where it can actually be caught. Same template approach as
 * {@link ExpiredRecordCleanup}.
 */
@Slf4j
@Component
public class SecurityEventRecorder {

    /**
     * Set by api-gateway's ClientAddressFilter, which drops any inbound copy first. Read instead
     * of {@code X-Forwarded-For} because the left-hand entries of that header are written by the
     * client: believing them would let an attacker choose the address their own break-in is filed
     * under, and a log that can be addressed by its subject is worse than no log.
     */
    private static final String CLIENT_IP_HEADER = "X-Client-Ip";

    /**
     * User-Agent Client Hints, which the browser only sends after it has seen this origin's
     * {@code Accept-CH} — that header is set by each front end's Next config, since the browser
     * talks to them and never to this service.
     *
     * <p>They exist because the user agent no longer carries either value: it is frozen for
     * privacy, so every Mac reports "Intel Mac OS X 10_15_7" whatever it actually is, and Chrome
     * on Android reports the model as the literal "K". Only Chromium implements the hints, and
     * {@code Sec-CH-UA-Model} is specified to be empty on every desktop platform, so expect both
     * to be absent most of the time.
     */
    private static final String MODEL_HEADER = "Sec-CH-UA-Model";
    private static final String PLATFORM_VERSION_HEADER = "Sec-CH-UA-Platform-Version";

    private static final int MAX_IP = 45;
    private static final int MAX_USER_AGENT = 512;
    private static final int MAX_MODEL = 64;
    private static final int MAX_DETAIL = 255;

    private final AuthSecurityEventRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final InternalApiToken internalApiToken;

    public SecurityEventRecorder(AuthSecurityEventRepository repository,
                                 PlatformTransactionManager transactionManager,
                                 InternalApiToken internalApiToken) {
        this.repository = repository;
        this.internalApiToken = internalApiToken;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(Long userId, SecurityEventType type) {
        record(userId, type, null);
    }

    /**
     * @param userId  the account the entry belongs to. Null is a no-op: a failed sign-in against
     *                an identifier that matches no account has no history to be filed under, and
     *                inventing one keyed on the typed string would turn the log into a way of
     *                finding out which usernames exist.
     * @param detail  a short phrase — the new address, why sessions were revoked. Never a
     *                password, a token or an OTP; this row is read by every admin.
     */
    public void record(Long userId, SecurityEventType type, String detail) {
        if (userId == null) {
            return;
        }

        try {
            HttpServletRequest request = currentRequest();
            String userAgent = header(request, HttpHeaders.USER_AGENT);
            String platformVersion = hint(request, PLATFORM_VERSION_HEADER);
            String model = hint(request, MODEL_HEADER);

            AuthSecurityEvent event = AuthSecurityEvent.builder()
                    .userId(userId)
                    .eventType(type)
                    .ipAddress(truncate(clientIp(request), MAX_IP))
                    .userAgent(truncate(userAgent, MAX_USER_AGENT))
                    .deviceLabel(DeviceLabel.of(userAgent, platformVersion, model))
                    .deviceModel(truncate(model, MAX_MODEL))
                    .detail(truncate(detail, MAX_DETAIL))
                    .build();

            transactionTemplate.executeWithoutResult(status -> repository.save(event));
        } catch (RuntimeException e) {
            log.error("Could not record security event {} for user {}", type, userId, e);
        }
    }

    /**
     * The request being served, or null on a thread that is not serving one — a Kafka consumer, a
     * scheduled sweep. Spring MVC already publishes this; a filter and a ThreadLocal of our own
     * would be the same mechanism built a second time.
     */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /**
     * Falls back to the socket's peer when the gateway header is absent, which means the service
     * was reached directly — local development, or a health probe. In a deployment that is the
     * gateway's own address, and recording that is honest: it says the hop the request arrived
     * from, rather than a client address nobody established.
     *
     * <p>The header counts only beside the internal API token, which api-gateway sends to this
     * service and no other. Anything that reaches this port without the gateway can write
     * X-Client-Ip however it likes, and for that caller the socket is the honest answer too.
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String resolved = request.getHeader(CLIENT_IP_HEADER);
        boolean fromGateway = internalApiToken.accepts(request.getHeader(InternalApiToken.HEADER));
        return fromGateway && resolved != null && !resolved.isBlank() ? resolved.strip() : request.getRemoteAddr();
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    /**
     * A client hint, unquoted. They are RFC 8941 structured-header strings, so the value arrives
     * as {@code "SM-A525F"} with the quotes included and would otherwise be stored that way.
     *
     * <p>An empty result becomes null rather than an empty string, because that is what it means:
     * every desktop Chromium answers {@code Sec-CH-UA-Model} with {@code ""}, and a column of
     * empty strings reads like data that failed to parse instead of a device that cannot be named.
     */
    private static String hint(HttpServletRequest request, String name) {
        String value = header(request, name);
        if (value == null) {
            return null;
        }

        String unquoted = value.strip();
        if (unquoted.length() >= 2 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }
        return unquoted.isBlank() ? null : unquoted;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
