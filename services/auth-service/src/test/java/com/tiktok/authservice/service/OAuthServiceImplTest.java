package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.SocialLinkRequest;
import com.tiktok.authservice.dto.request.SocialLoginRequest;
import com.tiktok.authservice.dto.response.SocialLoginResponse;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserIdentity;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.event.producer.SocialAvatarEventProducer;
import com.tiktok.authservice.exception.InvalidCredentialsException;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.SocialLinkVerificationRequiredException;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthServiceImplTest {

    private static final String UID = "107841234567890123456";
    private static final SocialLoginRequest REQUEST = new SocialLoginRequest("provider-token");

    private final SocialTokenVerifier verifier = mock(SocialTokenVerifier.class);
    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SocialAccountRegistrar registrar = mock(SocialAccountRegistrar.class);
    private final SocialLinkChallenge linkChallenge = mock(SocialLinkChallenge.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);
    private final SocialAvatarEventProducer avatarEventProducer = mock(SocialAvatarEventProducer.class);

    private OAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        when(verifier.provider()).thenReturn(AuthProvider.GOOGLE);
        when(verifier.verify(REQUEST.token()))
                .thenReturn(new SocialProfile(AuthProvider.GOOGLE, UID, "a@example.com", true));
        when(tokenIssuer.issue(any())).thenReturn(new TokenResponse("access", "refresh", 900_000L));

        service = new OAuthServiceImpl(List.of(verifier), identityRepository, userRepository,
                registrar, linkChallenge, tokenIssuer, avatarEventProducer);
    }

    /** A returning user is resolved by provider uid alone — no second account, no email involved. */
    @Test
    void returningUserIsNotRegisteredAgain() {
        linkedTo(activeUser("a@example.com"));

        SocialLoginResponse response = service.login(AuthProvider.GOOGLE, REQUEST);

        assertThat(response.tokens().accessToken()).isEqualTo("access");
        assertThat(response.requiresEmail()).isFalse();
        verify(registrar, never()).register(any());
    }

    @Test
    void firstLoginRegisters() {
        when(identityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, UID))
                .thenReturn(Optional.empty());
        when(registrar.register(any())).thenReturn(activeUser("a@example.com"));

        service.login(AuthProvider.GOOGLE, REQUEST);

        verify(registrar).register(any());
    }

    /**
     * Announced on every sign-in, not only the first: the provider URL expires, and an account
     * that predates this feature is only ever reachable while its owner is signing in.
     */
    @Test
    void everySignInAnnouncesTheProviderAvatar() {
        when(verifier.verify(REQUEST.token())).thenReturn(new SocialProfile(
                AuthProvider.GOOGLE, UID, "a@example.com", true, "https://lh3.googleusercontent.com/a/x"));
        User user = activeUser("a@example.com");
        linkedTo(user);

        service.login(AuthProvider.GOOGLE, REQUEST);

        verify(avatarEventProducer).publish(user.getId(), "https://lh3.googleusercontent.com/a/x");
    }

    @Test
    void signInWithoutAProviderPictureAnnouncesNothing() {
        linkedTo(activeUser("a@example.com"));

        service.login(AuthProvider.GOOGLE, REQUEST);

        verify(avatarEventProducer, never()).publish(any(), any());
    }

    /** An account with no address is a normal outcome for Facebook; the client is told to ask. */
    @Test
    void accountWithoutEmailAsksTheClientForOne() {
        linkedTo(activeUser(null));

        assertThat(service.login(AuthProvider.GOOGLE, REQUEST).requiresEmail()).isTrue();
    }

    @Test
    void lockedAccountGetsNoSession() {
        User locked = activeUser("a@example.com");
        locked.lock();
        linkedTo(locked);

        assertThatThrownBy(() -> service.login(AuthProvider.GOOGLE, REQUEST))
                .isInstanceOf(InvalidCredentialsException.class);
        verify(tokenIssuer, never()).issue(any());
    }

    /**
     * Two first-time logins for one provider account — a double tap — race to insert the same
     * uq_identity row. The loser must still get a session rather than a 500: both requests are the
     * same user asking the same thing.
     */
    @Test
    void loserOfTheRegistrationRaceJoinsTheWinnersAccount() {
        User winner = activeUser("a@example.com");
        when(identityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, UID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(identityFor(winner)));
        when(userRepository.findById(any())).thenReturn(Optional.of(winner));
        when(registrar.register(any())).thenThrow(new DataIntegrityViolationException("uq_identity"));

        assertThat(service.login(AuthProvider.GOOGLE, REQUEST).tokens().accessToken()).isEqualTo("access");
    }

    /**
     * The invented username collides with one a concurrent registration just took. Nothing about
     * the provider account is in conflict, so findLinked answers nothing and this used to reach the
     * client as a 500; the retry draws fresh digits against the winner's state instead.
     */
    @Test
    void loserOfTheUsernameRaceRegistersAgainInsteadOfFailing() {
        when(identityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, UID))
                .thenReturn(Optional.empty());
        when(registrar.register(any()))
                .thenThrow(new DataIntegrityViolationException("uq_users_username"))
                .thenReturn(activeUser("a@example.com"));

        assertThat(service.login(AuthProvider.GOOGLE, REQUEST).tokens().accessToken()).isEqualTo("access");
        verify(registrar, times(2)).register(any());
    }

    /** Losing twice is not a race any more, so it surfaces rather than being retried forever. */
    @Test
    void losingTheRaceTwiceIsNotRetriedAgain() {
        when(identityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, UID))
                .thenReturn(Optional.empty());
        when(registrar.register(any()))
                .thenThrow(new DataIntegrityViolationException("uq_users_username"));

        assertThatThrownBy(() -> service.login(AuthProvider.GOOGLE, REQUEST))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(registrar, times(2)).register(any());
    }

    /**
     * The Google-then-Facebook case that used to end in two accounts. Nothing is registered and no
     * session is issued; a code goes out instead, and the client is told to come back with it.
     */
    @Test
    void addressOwnedByAnotherAccountStartsAChallengeInsteadOfASecondAccount() {
        User owner = activeUser("a@example.com");
        when(identityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, UID))
                .thenReturn(Optional.empty());
        when(registrar.register(any())).thenThrow(new SocialLinkRequiredSignal(owner));

        assertThatThrownBy(() -> service.login(AuthProvider.GOOGLE, REQUEST))
                .isInstanceOf(SocialLinkVerificationRequiredException.class);

        verify(linkChallenge).start(eq(owner), any());
        verify(tokenIssuer, never()).issue(any());
    }

    /** The code proves the mailbox, the token proves the provider account — then, one session. */
    @Test
    void confirmedCodeLinksTheProviderToTheExistingAccount() {
        User owner = activeUser("a@example.com");
        when(identityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, UID))
                .thenReturn(Optional.empty());
        when(linkChallenge.confirm(any(), eq("123456"))).thenReturn(owner);

        SocialLoginResponse response =
                service.confirmLink(AuthProvider.GOOGLE, new SocialLinkRequest(REQUEST.token(), "123456"));

        assertThat(response.tokens().accessToken()).isEqualTo("access");
        assertThat(response.requiresEmail()).isFalse();
    }

    /** No address behind the token means no code was ever mailed, so none can be right. */
    @Test
    void confirmWithoutAnAddressIsRejected() {
        when(verifier.verify(REQUEST.token()))
                .thenReturn(new SocialProfile(AuthProvider.GOOGLE, UID, null, false));

        assertThatThrownBy(() -> service.confirmLink(AuthProvider.GOOGLE,
                new SocialLinkRequest(REQUEST.token(), "123456")))
                .isInstanceOf(InvalidOtpException.class);
        verify(linkChallenge, never()).confirm(any(), any());
    }

    private void linkedTo(User user) {
        when(identityRepository.findByProviderAndProviderUid(AuthProvider.GOOGLE, UID))
                .thenReturn(Optional.of(identityFor(user)));
        when(userRepository.findById(any())).thenReturn(Optional.of(user));
    }

    private UserIdentity identityFor(User user) {
        return UserIdentity.builder()
                .userId(1L)
                .provider(AuthProvider.GOOGLE)
                .providerUid(UID)
                .build();
    }

    private User activeUser(String email) {
        return User.builder()
                .username("someone")
                .email(email)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
