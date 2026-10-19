package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OtpProperties;
import com.tiktok.authservice.dto.request.CloseAccountRequest;
import com.tiktok.authservice.dto.response.DeletionScheduledResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.event.local.AccountClosureCodeRequestedEvent;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.exception.AccountClosureNotAllowedException;
import com.tiktok.authservice.exception.AccountDeactivatedException;
import com.tiktok.authservice.exception.AccountDeletionPendingException;
import com.tiktok.authservice.exception.EmailNotVerifiedException;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import com.tiktok.authservice.exception.ReconfirmationFailedException;
import com.tiktok.authservice.exception.UserNotFoundException;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AccountClosureServiceImpl implements AccountClosureService {

    /** Its own OTP budget, apart from verification and reset. */
    private static final String PURPOSE_CLOSURE = "account-closure";

    private final Map<AuthProvider, SocialTokenVerifier> verifiers = new EnumMap<>(AuthProvider.class);
    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final UserEventProducer userEventProducer;
    private final LoginRateLimiter loginRateLimiter;
    private final OtpService otpService;
    private final OtpRateLimiter otpRateLimiter;
    private final OtpProperties otpProperties;
    private final SessionRevoker sessionRevoker;
    private final SecurityEventRecorder securityEventRecorder;
    private final Duration deletionGrace;

    public AccountClosureServiceImpl(List<SocialTokenVerifier> verifiers,
                                     UserRepository userRepository,
                                     UserIdentityRepository userIdentityRepository,
                                     UserEventProducer userEventProducer,
                                     LoginRateLimiter loginRateLimiter,
                                     OtpService otpService,
                                     OtpRateLimiter otpRateLimiter,
                                     OtpProperties otpProperties,
                                     SessionRevoker sessionRevoker,
                                     SecurityEventRecorder securityEventRecorder,
                                     @Value("${auth.account.deletion-grace:30d}") Duration deletionGrace) {
        verifiers.forEach(verifier -> this.verifiers.put(verifier.provider(), verifier));
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.userEventProducer = userEventProducer;
        this.loginRateLimiter = loginRateLimiter;
        this.otpService = otpService;
        this.otpRateLimiter = otpRateLimiter;
        this.otpProperties = otpProperties;
        this.sessionRevoker = sessionRevoker;
        this.securityEventRecorder = securityEventRecorder;
        this.deletionGrace = deletionGrace;
    }

    @Override
    @Transactional
    public void sendClosureCode(Long userId) {
        User user = closable(userId);
        if (user.getEmail() == null || !user.isEmailVerified()) {
            throw new EmailNotVerifiedException();
        }
        otpRateLimiter.checkAllowed(PURPOSE_CLOSURE, user.getEmail());
        otpService.issue(user, VerificationTokenType.ACCOUNT_CLOSURE, otpProperties.passwordResetExpiryMillis(),
                otp -> new AccountClosureCodeRequestedEvent(user.getEmail(), otp));
    }

    @Override
    @Transactional
    public void deactivate(Long userId, CloseAccountRequest request) {
        close(userId, request, null);
    }

    @Override
    @Transactional
    public DeletionScheduledResponse requestDeletion(Long userId, CloseAccountRequest request) {
        Instant deletionScheduledAt = Instant.now().plus(deletionGrace);
        close(userId, request, deletionScheduledAt);
        return new DeletionScheduledResponse(deletionScheduledAt);
    }

    /**
     * The sessions end from their own transaction, before this one commits — the order a ban uses
     * too. A rollback after it leaves the owner signed out of an account still open, which only
     * costs them a sign-in.
     */
    private void close(Long userId, CloseAccountRequest request, Instant deletionScheduledAt) {
        User user = closable(userId);
        requireOwner(user, request);

        Instant now = Instant.now();
        user.deactivate(now, deletionScheduledAt);
        userRepository.save(user);
        userEventProducer.publishAccountStateChanged(
                AccountStateChangedEvent.of(userId, AccountState.DEACTIVATED, deletionScheduledAt));

        sessionRevoker.revokeAllSessions(userId);
        securityEventRecorder.record(userId, SecurityEventType.ACCOUNT_DEACTIVATED,
                deletionScheduledAt == null ? "deactivated" : "deletion scheduled for " + deletionScheduledAt);
        log.info("User {} closed their account, deletion scheduled at {}", userId, deletionScheduledAt);
    }

    /** Open, not an admin. A closed or banned account holds no session to be here with anyway. */
    private User closable(Long userId) {
        User user = userRepository.findById(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new UserNotFoundException(String.valueOf(userId)));
        if (user.getRole() == UserRole.ADMIN) {
            throw new AccountClosureNotAllowedException();
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }
        return user;
    }

    /**
     * Whichever of the three proofs the request carries, held to the same budget as the sign-in it
     * stands in for: a password shares the account's login counter, so a session left open does
     * not become a way to guess it without limit.
     */
    private void requireOwner(User user, CloseAccountRequest request) {
        if (request.password() != null && !request.password().isBlank()) {
            String key = "user:" + user.getId();
            loginRateLimiter.acquireAttempt(key);
            if (user.getPasswordHash() == null || !HashUtils.bcryptMatches(request.password(), user.getPasswordHash())) {
                throw ReconfirmationFailedException.wrongPassword();
            }
            loginRateLimiter.recordSuccess(key);
        } else if (request.otp() != null && !request.otp().isBlank()) {
            if (user.getEmail() == null) {
                throw new InvalidOtpException();
            }
            User owner = otpService.consume(PURPOSE_CLOSURE, VerificationTokenType.ACCOUNT_CLOSURE,
                    user.getEmail(), request.otp());
            if (!owner.getId().equals(user.getId())) {
                throw new InvalidOtpException();
            }
        } else {
            SocialProfile profile;
            try {
                profile = verifiers.get(request.provider()).verify(request.token());
            } catch (InvalidSocialTokenException e) {
                throw ReconfirmationFailedException.wrongSocialToken();
            }
            // A link waiting to be confirmed again signs nobody in, so it proves nobody here either.
            boolean linked = userIdentityRepository.findByProviderAndProviderUid(profile.provider(), profile.uid())
                    .filter(identity -> identity.getUserId().equals(user.getId()))
                    .filter(identity -> !identity.isNeedsReconfirmation())
                    .isPresent();
            if (!linked) {
                throw ReconfirmationFailedException.wrongSocialToken();
            }
        }
    }

    @Override
    @Transactional
    public User admit(User user, boolean reactivate) {
        if (user.getStatus() != UserStatus.DEACTIVATED) {
            return user;
        }
        if (!reactivate) {
            throw user.getDeletionScheduledAt() != null
                    ? new AccountDeletionPendingException()
                    : new AccountDeactivatedException();
        }

        User current = userRepository.findById(user.getId())
                .filter(found -> !found.isDeleted() && found.getStatus() == UserStatus.DEACTIVATED)
                // Deleted, banned or reopened since the credential was checked: banned and deleted
                // answer as everywhere else; reopened just carries on.
                .orElseGet(() -> userRepository.findById(user.getId())
                        .filter(found -> !found.isDeleted() && found.getStatus() == UserStatus.ACTIVE)
                        .orElseThrow(InvalidCredentialsException::new));
        if (current.getStatus() == UserStatus.ACTIVE) {
            return current;
        }

        current.reactivate();
        User saved = userRepository.save(current);
        userEventProducer.publishAccountStateChanged(
                AccountStateChangedEvent.of(saved.getId(), AccountState.ACTIVE, null));
        securityEventRecorder.record(saved.getId(), SecurityEventType.ACCOUNT_REACTIVATED);
        log.info("User {} reopened their account", saved.getId());
        return saved;
    }

    /**
     * Re-reads the row, so a reopening that landed after the sweep listed it is respected. A ban
     * laid on top does not stop it: the owner asked for the deletion, and the ban is moot once the
     * account is gone.
     */
    @Override
    @Transactional
    public void eraseIfDue(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isDeleted() || user.getDeletionScheduledAt() == null
                || user.getDeletionScheduledAt().isAfter(Instant.now())) {
            return;
        }

        user.erase();
        userRepository.save(user);
        int links = userIdentityRepository.deleteAllByUserId(userId);
        userEventProducer.publishAccountStateChanged(
                AccountStateChangedEvent.of(userId, AccountState.DELETED, null));
        sessionRevoker.revokeAllSessions(userId);
        log.info("Deleted user {} as scheduled; {} provider link(s) removed", userId, links);
    }
}
