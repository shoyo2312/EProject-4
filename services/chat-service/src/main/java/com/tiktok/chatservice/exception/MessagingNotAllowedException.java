package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The recipient's account-level DM audience refuses this sender — they are not a mutual follower,
 * or the recipient accepts messages from nobody.
 *
 * <p>Separate from {@link MessagingBlockedException}: a block is mutual and personal, an audience
 * is a standing setting that happens to exclude you. The client says different things about them,
 * and a block must never be inferable from a setting.
 */
public class MessagingNotAllowedException extends ForbiddenException {

    public MessagingNotAllowedException() {
        super("MESSAGING_NOT_ALLOWED", "This account does not accept messages from you");
    }
}
