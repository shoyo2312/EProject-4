package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OtpProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserIdentity;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.SocialSignInNotAllowedException;
import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import com.tiktok.authservice.repository.UserIdentityRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The security argument for the link flow lives in this class, so it gets tests of its own rather
 * than only being mocked out of {@link OAuthServiceImplTest}: what may be linked, and what a spent
 * send budget is allowed to do to the answer.
 */
class SocialLinkChallengeTest {

    private static final String EMAIL = "a@example.com";
    private static final SocialProfile PROFILE =
            new SocialProfile(AuthProvider.FACEBOOK, "fb-uid-1", EMAIL, false);

    private final OtpService otpService = mock(OtpService.class);
    private final OtpRateLimiter otpRateLimiter = mock(OtpRateLimiter.class);
    private final SocialAccountRegistrar registrar = mock(SocialAccountRegistrar.class);
    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final OtpProperties otpProperties = new OtpProperties(900_000L, 900_000L, 300_000L);

    private final SocialLinkChallenge challenge =
            new SocialLinkChallenge(otpService, otpRateLimiter, otpProperties, registrar, identityRepository);

    @Test
    void startMailsACodeAgainstItsOwnRateLimitNamespace() {
        challenge.start(activeUser(), PROFILE);

        verify(otpRateLimiter).checkAllowed(SocialLinkChallenge.PURPOSE, EMAIL);
        verify(otpService).issue(any(), eq(VerificationTokenType.SOCIAL_LINK), anyLong(), any());
    }

    /**
     * A spent send budget must not turn the caller's 409 into a 429: the budget runs out because
     * the user kept pressing sign-in, and "try again later" is the one instruction that does not
     * lead them to the code already sitting in their mailbox.
     */
    @Test
    void spentSendBudgetSkipsTheMailButNotTheChallenge() {
        doThrow(new TooManyOtpRequestsException())
                .when(otpRateLimiter).checkAllowed(SocialLinkChallenge.PURPOSE, EMAIL);

        assertThatCode(() -> challenge.start(activeUser(), PROFILE)).doesNotThrowAnyException();

        verify(otpService, never()).issue(any(), any(), anyLong(), any());
    }

    @Test
    void confirmedCodeLinksTheProviderAccount() {
        User owner = activeUser();
        when(otpService.consume(SocialLinkChallenge.PURPOSE, VerificationTokenType.SOCIAL_LINK,
                EMAIL, "123456")).thenReturn(owner);

        assertThat(challenge.confirm(PROFILE, "123456")).isSameAs(owner);

        verify(registrar).claim(owner, PROFILE);
    }

    /**
     * The link outlives the sign-in it was meant to serve, so a locked account has to be refused
     * before the row is written — refusing the session afterwards would leave the provider account
     * attached to an account nobody may sign in to.
     */
    @Test
    void lockedOwnerIsNotLinked() {
        User locked = activeUser();
        locked.lock();
        when(otpService.consume(SocialLinkChallenge.PURPOSE, VerificationTokenType.SOCIAL_LINK,
                EMAIL, "123456")).thenReturn(locked);

        assertThatThrownBy(() -> challenge.confirm(PROFILE, "123456"))
                .isInstanceOf(InvalidOtpException.class);

        verify(registrar, never()).claim(any(), any());
    }

    @Test
    void adminOwnerIsNotLinked() {
        User admin = activeUser();
        admin.promoteToAdmin();
        when(otpService.consume(SocialLinkChallenge.PURPOSE, VerificationTokenType.SOCIAL_LINK,
                EMAIL, "123456")).thenReturn(admin);

        assertThatThrownBy(() -> challenge.confirm(PROFILE, "123456"))
                .isInstanceOf(SocialSignInNotAllowedException.class);

        verify(registrar, never()).claim(any(), any());
    }

    /**
     * A link waiting to be confirmed again may come from a provider account with no address, or a
     * different one; the code goes to the address this account is known by.
     */
    @Test
    void startMailsTheOwnersAddressNotTheProviders() {
        challenge.start(activeUser(), new SocialProfile(AuthProvider.FACEBOOK, "fb-uid-1", null, false));

        verify(otpRateLimiter).checkAllowed(SocialLinkChallenge.PURPOSE, EMAIL);
        verify(otpService).issue(any(), eq(VerificationTokenType.SOCIAL_LINK), anyLong(), any());
    }

    @Test
    void reconfirmSpendsTheOwnersCodeAndLetsTheLinkSignInAgain() {
        User owner = activeUser();
        UserIdentity flagged = flaggedLinkTo(owner);
        when(otpService.consume(SocialLinkChallenge.PURPOSE, VerificationTokenType.SOCIAL_LINK,
                EMAIL, "123456")).thenReturn(owner);

        assertThat(challenge.reconfirm(owner, flagged, "123456")).isSameAs(owner);

        assertThat(flagged.isNeedsReconfirmation()).isFalse();
        verify(identityRepository).save(flagged);
    }

    /** Only ever the account the link points at, whatever the code happened to resolve to. */
    @Test
    void reconfirmRefusesACodeThatOpensAnotherAccount() {
        User owner = activeUser();
        User someoneElse = User.builder().id(99L).username("other").email(EMAIL)
                .role(UserRole.USER).status(UserStatus.ACTIVE).build();
        UserIdentity flagged = flaggedLinkTo(owner);
        when(otpService.consume(SocialLinkChallenge.PURPOSE, VerificationTokenType.SOCIAL_LINK,
                EMAIL, "123456")).thenReturn(someoneElse);

        assertThatThrownBy(() -> challenge.reconfirm(owner, flagged, "123456"))
                .isInstanceOf(InvalidOtpException.class);

        assertThat(flagged.isNeedsReconfirmation()).isTrue();
        verify(identityRepository, never()).save(any());
    }

    private UserIdentity flaggedLinkTo(User owner) {
        return UserIdentity.builder()
                .userId(owner.getId())
                .provider(AuthProvider.FACEBOOK)
                .providerUid("fb-uid-1")
                .needsReconfirmation(true)
                .build();
    }

    private User activeUser() {
        return User.builder()
                .id(7L)
                .username("someone")
                .email(EMAIL)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
