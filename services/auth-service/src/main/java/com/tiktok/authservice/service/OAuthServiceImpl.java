package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.SocialLinkRequest;
import com.tiktok.authservice.dto.request.SocialLoginRequest;
import com.tiktok.authservice.dto.response.SocialLoginResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.SocialLinkVerificationRequiredException;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class OAuthServiceImpl implements OAuthService {

    private final Map<AuthProvider, SocialTokenVerifier> verifiers = new EnumMap<>(AuthProvider.class);
    private final UserIdentityRepository userIdentityRepository;
    private final UserRepository userRepository;
    private final SocialAccountRegistrar registrar;
    private final SocialLinkChallenge linkChallenge;
    private final TokenIssuer tokenIssuer;

    public OAuthServiceImpl(List<SocialTokenVerifier> verifiers,
                            UserIdentityRepository userIdentityRepository,
                            UserRepository userRepository,
                            SocialAccountRegistrar registrar,
                            SocialLinkChallenge linkChallenge,
                            TokenIssuer tokenIssuer) {
        verifiers.forEach(verifier -> this.verifiers.put(verifier.provider(), verifier));
        this.userIdentityRepository = userIdentityRepository;
        this.userRepository = userRepository;
        this.registrar = registrar;
        this.linkChallenge = linkChallenge;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * Deliberately not {@code @Transactional}: creating the account has to be able to fail on its
     * own, inside {@link SocialAccountRegistrar}, so that losing the uq_identity race leaves this
     * method able to read the winner's row. A shared transaction would already be doomed by then.
     */
    @Override
    public SocialLoginResponse login(AuthProvider provider, SocialLoginRequest request) {
        SocialProfile profile = verifiers.get(provider).verify(request.token());

        User user = findLinked(profile).orElseGet(() -> registerOrJoinWinner(profile));

        // A locked account must not be handed a session by the side door. Same answer the password
        // login gives, for the same reason: why the sign-in failed is not the client's business.
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        // Not checked: emailVerified. The provider has already proven the user controls the
        // account, and an account with no address at all could never satisfy it.
        return new SocialLoginResponse(tokenIssuer.issue(user), user.getEmail() == null);
    }

    /**
     * Confirms the mailed code and merges, instead of leaving the same person with one account per
     * provider.
     *
     * <p>Not {@code @Transactional} for the same reason {@link #login} is not: the link insert has
     * to be able to lose its own race inside {@link SocialLinkChallenge} without taking this method
     * down with it.
     */
    @Override
    public SocialLoginResponse confirmLink(AuthProvider provider, SocialLinkRequest request) {
        SocialProfile profile = verifiers.get(provider).verify(request.token());

        // No address means no challenge was ever mailed for this provider account, so there is no
        // code that could be right. Answered as a bad code, not as "you asked the wrong endpoint".
        if (profile.email() == null) {
            throw new InvalidOtpException();
        }

        // Already linked: a resubmitted confirmation, or a second tab that finished first. The
        // code it carries is spent or about to be, and re-spending it is not required to prove
        // anything — the identity row already says this provider account is ours.
        User user = findLinked(profile).orElseGet(() -> confirmOrJoinWinner(profile, request.otp()));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidCredentialsException();
        }

        return new SocialLoginResponse(tokenIssuer.issue(user), user.getEmail() == null);
    }

    private User confirmOrJoinWinner(SocialProfile profile, String otp) {
        try {
            return linkChallenge.confirm(profile, otp);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent link confirmation for {} uid {} — joining the winning link",
                    profile.provider(), profile.uid());
            return findLinked(profile).orElseThrow(() -> e);
        }
    }

    private User registerOrJoinWinner(SocialProfile profile) {
        try {
            return attemptRegister(profile);
        } catch (DataIntegrityViolationException e) {
            // A uniqueness race was lost, and every index it could have been is answered the same
            // way — by looking again at what the winner left behind:
            //
            //   uq_identity       a concurrent first login for this same provider account. The
            //                     winner's row is there now, so read it back and log in.
            //   uq_users_username the invented username collided. It is random digits drawn
            //                     against a read that is now stale, so a retry draws again.
            //   uq_users_email    a concurrent first login for a different provider account behind
            //                     the same address. The account exists now, so the retry takes the
            //                     link-or-challenge branch instead of creating a second account.
            //
            // Without the retry the last two reach the caller as a 500, because findLinked answers
            // nothing for a race that was never about the identity. The retry has to be a fresh
            // transaction, which is why it re-enters the registrar rather than looping inside it:
            // that transaction is already doomed by the violation and can read nothing.
            log.info("Concurrent first login for {} uid {} — retrying against the winner's state",
                    profile.provider(), profile.uid());
            return findLinked(profile).orElseGet(() -> attemptRegister(profile));
        }
    }

    /**
     * One registration attempt, with the "this address is spoken for" signal turned into the 409.
     *
     * <p>Deliberately does not catch {@link DataIntegrityViolationException}: losing twice in a row
     * is not a race any more, so the second violation propagates as a 500 with its stack trace
     * rather than being retried again.
     */
    private User attemptRegister(SocialProfile profile) {
        try {
            return registrar.register(profile);
        } catch (SocialLinkRequiredSignal signal) {
            // The address is spoken for and the provider does not vouch for it. Mail the code from
            // a transaction of its own — the registrar's has just rolled back — and tell the client
            // to come back with it.
            linkChallenge.start(signal.owner(), profile);
            throw new SocialLinkVerificationRequiredException();
        }
    }

    private Optional<User> findLinked(SocialProfile profile) {
        return userIdentityRepository
                .findByProviderAndProviderUid(profile.provider(), profile.uid())
                .map(identity -> userRepository.findById(identity.getUserId())
                        .filter(user -> !user.isDeleted())
                        // The link outlives the account it points at, so a deleted account would
                        // otherwise send us down the create path and straight into uq_identity.
                        .orElseThrow(InvalidCredentialsException::new));
    }
}
