package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.UserIdentityRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which links a takeover by the mailbox owner flags, and when the sessions go with them.
 */
class IdentityReconfirmationTest {

    private final UserIdentityRepository identityRepository = mock(UserIdentityRepository.class);
    private final SessionRevoker sessionRevoker = mock(SessionRevoker.class);

    private final IdentityReconfirmation reconfirmation =
            new IdentityReconfirmation(identityRepository, sessionRevoker);

    /** Never proven, so nothing linked to it was ever linked against a proven address. */
    @Test
    void unverifiedAccount_flagsEveryLinkAndRevokesTheSquattersSessions() {
        User squatted = user(false, null);
        when(identityRepository.requireReconfirmationOfAll(7L)).thenReturn(1);

        reconfirmation.onMailboxOwnerTakesOver(squatted);

        verify(identityRepository).requireReconfirmationOfAll(7L);
        verify(sessionRevoker).revokeAllSessions(7L);
    }

    /**
     * Verified already — by the victim being walked through /verify-email on an account they
     * never made, or by an honest owner. Only what was linked before that counts as unproven.
     */
    @Test
    void verifiedAccount_flagsOnlyLinksMadeBeforeTheAddressWasProven() {
        Instant provenAt = Instant.parse("2026-09-01T00:00:00Z");
        User owner = user(true, provenAt);
        when(identityRepository.requireReconfirmationOfLinkedBefore(7L, provenAt)).thenReturn(1);

        reconfirmation.onMailboxOwnerTakesOver(owner);

        verify(identityRepository).requireReconfirmationOfLinkedBefore(7L, provenAt);
        verify(identityRepository, never()).requireReconfirmationOfAll(anyLong());
        verify(sessionRevoker).revokeAllSessions(7L);
    }

    /** Nothing flagged means nobody else could have been signing in, so nobody is signed out. */
    @Test
    void nothingFlagged_leavesSessionsAlone() {
        User squatted = user(false, null);
        when(identityRepository.requireReconfirmationOfAll(7L)).thenReturn(0);

        reconfirmation.onMailboxOwnerTakesOver(squatted);

        verify(sessionRevoker, never()).revokeAllSessions(any());
    }

    /** No record of when it was proven, so nothing to cut against — left as it was. */
    @Test
    void verifiedWithoutATimestamp_isLeftAlone() {
        reconfirmation.onMailboxOwnerTakesOver(user(true, null));

        verifyNoInteractions(identityRepository, sessionRevoker);
    }

    private static User user(boolean verified, Instant verifiedAt) {
        return User.builder()
                .id(7L)
                .username("someone")
                .email("a@example.com")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .emailVerified(verified)
                .emailVerifiedAt(verifiedAt)
                .build();
    }
}
