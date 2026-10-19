package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.repository.UserIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * What happens to an account's provider links when the owner of its mailbox takes it over.
 *
 * <p>Pre-account takeover, the social half. Anyone can put {@code victim@example.com} on a
 * Facebook account and sign up with it, or sign up with no address and add the victim's through
 * {@code POST /auth/email}; the address stays unverified only because they cannot read the mail.
 * When the real owner later claims it — Google vouching for the address, a spent link code, or a
 * password reset — they get the account, and it used to come with the squatter's Facebook link
 * still attached: a second, working way in to the account the victim now uses, with the
 * squatter's sessions still alive on top. Dropping the squatter's password (see
 * {@link SocialAccountRegistrar#claim}) closed the same hole for password signups only.
 *
 * <p>Every link made before the address was proven is therefore flagged, and signs in again only
 * after a code mailed to the address is spent — which the squatter cannot read and the owner can.
 * Flagged rather than deleted: the same shape describes an honest user who signed up through
 * Facebook and added their own address later, and for them it costs one code, where deleting the
 * link would turn their next Facebook sign-in without an email into a second, empty account.
 *
 * <p>Deliberately not run when the address is merely verified through {@code /verify-email}. That
 * step is how that same honest user confirms the address they just added, and flagging there
 * would log them out the moment they finished. It is also not a takeover: verifying grants the
 * mailbox owner no way in. The takeovers that follow it do run this, and the links made before
 * that verification are still caught by the cutoff below.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdentityReconfirmation {

    private final UserIdentityRepository userIdentityRepository;
    private final SessionRevoker sessionRevoker;

    /**
     * Called when the owner of {@code user}'s address has just proven it and is taking the
     * account over, before the account is marked verified — the cutoff is when the address was
     * first proven, which that would overwrite.
     *
     * <p>Sessions go too whenever a link was flagged: a social sign-in needs no verified address,
     * so the squatter may already be holding a refresh token, and flagging the link does nothing
     * about a session it already issued. The caller's own session is minted after this.
     */
    public void onMailboxOwnerTakesOver(User user) {
        int flagged;
        if (!user.isEmailVerified()) {
            flagged = userIdentityRepository.requireReconfirmationOfAll(user.getId());
        } else if (user.getEmailVerifiedAt() != null) {
            flagged = userIdentityRepository.requireReconfirmationOfLinkedBefore(
                    user.getId(), user.getEmailVerifiedAt());
        } else {
            // Verified before the timestamp was recorded: nothing to cut against, and treating it
            // as never proven would flag links its owner made against an address long confirmed.
            return;
        }

        if (flagged > 0) {
            log.info("Account {} taken over by its address owner; {} provider link(s) must be "
                    + "confirmed again and every session is revoked", user.getId(), flagged);
            sessionRevoker.revokeAllSessions(user.getId());
        }
    }
}
