package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.SocialLoginRequest;
import com.tiktok.authservice.dto.response.SocialLoginResponse;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserIdentity;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.exception.InvalidCredentialsException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthServiceImplTest {

    private static final String UID = "107841234567890123456";
    private static final SocialLoginRequest REQUEST = new SocialLoginRequest("provider-token");

    private final SocialTokenVerifier verifier = mock(SocialTokenVerifier.class);
    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SocialAccountRegistrar registrar = mock(SocialAccountRegistrar.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);

    private OAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        when(verifier.provider()).thenReturn(AuthProvider.GOOGLE);
        when(verifier.verify(REQUEST.token()))
                .thenReturn(new SocialProfile(AuthProvider.GOOGLE, UID, "a@example.com", true));
        when(tokenIssuer.issue(any())).thenReturn(new TokenResponse("access", "refresh", 900_000L));

        service = new OAuthServiceImpl(List.of(verifier), identityRepository, userRepository,
                registrar, tokenIssuer);
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
