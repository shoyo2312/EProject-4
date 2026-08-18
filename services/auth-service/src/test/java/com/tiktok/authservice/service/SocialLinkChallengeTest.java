package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OtpProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.TooManyOtpRequestsException;
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
    private final OtpProperties otpProperties = new OtpProperties(900_000L, 900_000L);

    private final SocialLinkChallenge challenge =
            new SocialLinkChallenge(otpService, otpRateLimiter, otpProperties, registrar);

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

        verify(registrar).link(owner, PROFILE);
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

        verify(registrar, never()).link(any(), any());
    }

    private User activeUser() {
        return User.builder()
                .username("someone")
                .email(EMAIL)
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
    }
}
