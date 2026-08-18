package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OtpProperties;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.entity.VerificationTokenType;
import com.tiktok.authservice.event.local.SocialLinkRequestedEvent;
import com.tiktok.authservice.exception.InvalidOtpException;
import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two halves of attaching a provider account to an account of ours that was found by email
 * alone: mail a code to that address, and later spend it.
 *
 * <p>This exists because a provider that does not assert an address is verified cannot be trusted
 * with one. Facebook never does — anyone can put {@code victim@example.com} on a Facebook account
 * — so the provider token proves only that the caller holds the Facebook account. The code proves
 * the other half, that they also hold the mailbox. Neither alone may merge anything, and that is
 * the entire security argument for this class.
 */
@Component
@RequiredArgsConstructor
public class SocialLinkChallenge {

    /** Its own rate-limit namespace, so link attempts cannot spend a signup's send budget. */
    static final String PURPOSE = "social-link";

    private final OtpService otpService;
    private final OtpRateLimiter otpRateLimiter;
    private final OtpProperties otpProperties;
    private final SocialAccountRegistrar registrar;

    /**
     * Mails the code. Returns normally — the caller throws — because the OTP row must survive this
     * transaction, and throwing from inside it would roll the row back and mail a code that could
     * never be spent.
     */
    @Transactional
    public void start(User owner, SocialProfile profile) {
        try {
            otpRateLimiter.checkAllowed(PURPOSE, profile.email());
        } catch (TooManyOtpRequestsException e) {
            // Send budget spent, so no new mail goes out — but the caller still answers 409, not
            // 429. The budget runs out precisely because the user kept pressing "sign in with
            // Facebook", which is what someone who has not understood the 409 does; answering "try
            // again later" there replaces the one instruction they can act on — enter the code we
            // mailed — with one they cannot. A code from the last 15 minutes is still live and
            // still spendable, so the 409 stays true.
            return;
        }

        otpService.issue(owner, VerificationTokenType.SOCIAL_LINK,
                otpProperties.emailVerificationExpiryMillis(),
                otp -> new SocialLinkRequestedEvent(profile.email(), otp, profile.provider()));
    }

    /**
     * Spends the code and creates the link, answering with the account now reachable through this
     * provider.
     *
     * <p>The code is looked up by the address the provider just gave us, so a code mailed for one
     * address cannot authorise a link for another; and the account it resolves to is by
     * construction the one that owns that address.
     */
    @Transactional
    public User confirm(SocialProfile profile, String otp) {
        User owner = otpService.consume(PURPOSE, VerificationTokenType.SOCIAL_LINK,
                profile.email(), otp);

        // A locked account is not a link target, and the check has to land here rather than on the
        // response: the link below outlives the sign-in it was meant to serve, so rejecting the
        // session afterwards still leaves the provider account permanently attached to an account
        // nobody may sign in to. Answered as a bad code rather than "that account is locked", for
        // the same reason the address is missing from the 409 — the caller has proven nothing
        // about this account yet.
        //
        // Deletion needs no check of its own: consume resolves the owner through
        // findByEmailIgnoreCaseAndDeletedAtIsNull, so a deleted account never reaches here.
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new InvalidOtpException();
        }

        registrar.link(owner, profile);
        return owner;
    }
}
