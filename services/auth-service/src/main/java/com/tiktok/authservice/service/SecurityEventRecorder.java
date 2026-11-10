package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.AuthSecurityEvent;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.repository.AuthSecurityEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Appends to an account's security log: what happened, from which address, which browser and
 * roughly where. What the request says about itself is read by {@link RequestOrigins}.
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
 * {@link ExpiredRecordCleanup}. The flip side: a value past its column fails the INSERT, the
 * failure is swallowed, and the whole entry is gone — so every client-supplied value is cut to
 * its column first.
 */
@Slf4j
@Component
public class SecurityEventRecorder {

    private static final int MAX_IP = 45;
    private static final int MAX_USER_AGENT = 512;
    private static final int MAX_MODEL = 64;
    private static final int MAX_LOCATION = 128;
    private static final int MAX_DETAIL = 255;

    private final AuthSecurityEventRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final RequestOrigins requestOrigins;

    public SecurityEventRecorder(AuthSecurityEventRepository repository,
                                 PlatformTransactionManager transactionManager,
                                 RequestOrigins requestOrigins) {
        this.repository = repository;
        this.requestOrigins = requestOrigins;
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
            write(userId, type, detail, requestOrigins.current());
        } catch (RuntimeException e) {
            log.error("Could not record security event {} for user {}", type, userId, e);
        }
    }

    /**
     * The same, with the request already read. A sign-in reads it once for its new-device check
     * and hands it on, so the entry and the alert name the same device and the same place.
     */
    public void record(Long userId, SecurityEventType type, String detail, RequestOrigin origin) {
        if (userId == null) {
            return;
        }

        try {
            write(userId, type, detail, origin == null ? RequestOrigin.NONE : origin);
        } catch (RuntimeException e) {
            log.error("Could not record security event {} for user {}", type, userId, e);
        }
    }

    private void write(Long userId, SecurityEventType type, String detail, RequestOrigin origin) {
        AuthSecurityEvent event = AuthSecurityEvent.builder()
                .userId(userId)
                .eventType(type)
                .ipAddress(truncate(origin.address(), MAX_IP))
                .userAgent(truncate(origin.userAgent(), MAX_USER_AGENT))
                .deviceLabel(origin.deviceLabel())
                .deviceModel(truncate(origin.model(), MAX_MODEL))
                .location(truncate(origin.location(), MAX_LOCATION))
                .detail(truncate(detail, MAX_DETAIL))
                .build();

        transactionTemplate.executeWithoutResult(status -> repository.save(event));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
