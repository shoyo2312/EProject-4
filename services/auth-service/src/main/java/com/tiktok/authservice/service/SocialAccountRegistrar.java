package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserIdentity;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.event.user.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Gives a provider account somewhere to point: an account of ours it may sign in to, plus the
 * {@code user_identities} row that will resolve it directly next time.
 *
 * <p>Its own bean, and its own transaction, because the insert is allowed to lose. Two first-time
 * logins with the same provider account — a double tap on a slow connection — both find no identity
 * and both come here; {@code uq_identity} lets exactly one through and the other's transaction
 * dies. That transaction has to be this one and not the caller's, so the caller can catch the
 * violation, read back what the winner wrote, and log the user in anyway.
 */
@Component
@RequiredArgsConstructor
public class SocialAccountRegistrar {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final UsernameGenerator usernameGenerator;
    private final UserEventProducer userEventProducer;

    @Transactional
    public User register(SocialProfile profile) {
        // Verification decides two separate things, and conflating them costs the user a screen for
        // nothing. It decides whether we may hand over an account we already hold — there it is
        // absolute, since anyone can register victim@example.com at a provider. It does not have to
        // decide whether a brand new account may carry the address: if nobody holds it, storing it
        // takes nothing from anyone, and the user is spared the add-email step.
        String email = profile.email();

        User existing = email == null
                ? null
                : userRepository.findByEmailIgnoreCaseAndDeletedAtIsNull(email).orElse(null);

        if (existing != null) {
            if (profile.emailVerified()) {
                link(existing, profile);
                return existing;
            }
            // Address already has an owner and the provider vouches for nothing. Both ways out of
            // here are wrong on their own: claiming the account hands it over on an unverified
            // claim, and starting a clean account leaves one person holding two of them for the
            // same address — which is exactly what Google-then-Facebook used to produce. So
            // neither; hand back to the caller, which mails a code and answers 409.
            throw new SocialLinkRequiredSignal(existing);
        }

        // Nobody holds the address, so a new account may carry it: storing it takes nothing from
        // anyone, and the user is spared the add-email step.
        boolean verified = email != null && profile.emailVerified();

        User user = userRepository.saveAndFlush(User.builder()
                // Generated from the address the provider gave even when we do not store it: the
                // name is cosmetic, and "nguyen.van.a" reads better than "user".
                .username(usernameGenerator.generate(profile.email()))
                .email(email)
                // No password hash. Signing in by password stays impossible for this account until
                // the user sets one through the reset flow — which, for an address only the
                // provider has asserted, is also what finally verifies it.
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .emailVerified(verified)
                .emailVerifiedAt(verified ? Instant.now() : null)
                .build());

        link(user, profile);

        // Same event the password signup publishes: without it user-service never creates a
        // profile, and the account exists only as a login.
        userEventProducer.publishUserRegistered(
                UserRegisteredEvent.of(user.getId(), user.getUsername(), user.getEmail()));

        return user;
    }

    /**
     * Flushed rather than merely saved: the id is assigned, so Hibernate would otherwise defer the
     * INSERT to commit and the uq_identity violation would surface after this method returned,
     * outside the caller's catch. Same reasoning as {@code AuthServiceImpl.saveUnique}.
     *
     * <p>Public for {@link SocialLinkChallenge}, which links an account the OTP has just proven
     * ownership of. One insert, one place: uq_identity is what makes a link unique, and a second
     * copy of this would be a second thing to keep in step with it.
     *
     * <p>No {@code @Transactional} of its own, and one here would be a lie: both callers are
     * already inside a transaction — {@link #register} above by self-invocation, which the Spring
     * proxy never sees, and {@code SocialLinkChallenge.confirm} through its own annotation. The row
     * has to live or die with the caller's work either way.
     */
    public void link(User user, SocialProfile profile) {
        userIdentityRepository.saveAndFlush(UserIdentity.builder()
                .userId(user.getId())
                .provider(profile.provider())
                .providerUid(profile.uid())
                .build());
    }
}
