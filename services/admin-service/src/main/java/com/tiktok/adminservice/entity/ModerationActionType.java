package com.tiktok.adminservice.entity;

import com.tiktok.adminservice.exception.InvalidModerationTargetException;

/**
 * What an admin decided, and which kind of target the decision makes sense against.
 *
 * <p>The pairing is enforced rather than assumed because resolving a report carries the report's
 * targetType and the admin's chosen actionType separately, and nothing in the request ties them
 * together. BAN_USER against a VIDEO report published a UserBannedEvent holding a Mongo document
 * id where a user id belongs — no exception on the consumer side, no log, just a ban aimed at
 * nobody; TAKEDOWN_VIDEO against a USER report is the same mistake pointing the other way.
 */
public enum ModerationActionType {

    BAN_USER(ReportTargetType.USER),
    UNBAN_USER(ReportTargetType.USER),
    TAKEDOWN_VIDEO(ReportTargetType.VIDEO),
    RESTORE_VIDEO(ReportTargetType.VIDEO),
    REMOVE_COMMENT(ReportTargetType.COMMENT),
    /** Audit-only, and an admin may warn over any kind of report, so nothing to pin it to. */
    WARN_USER(null),
    /** Audit-only: closing a report without acting says nothing about what it pointed at. */
    DISMISS_REPORT(null);

    private final ReportTargetType appliesTo;

    ModerationActionType(ReportTargetType appliesTo) {
        this.appliesTo = appliesTo;
    }

    /** @throws InvalidModerationTargetException if this action cannot be taken against that target. */
    public void requireApplicableTo(ReportTargetType targetType) {
        if (appliesTo != null && appliesTo != targetType) {
            throw new InvalidModerationTargetException(
                    this + " applies to a " + appliesTo + " target, but the target is a " + targetType);
        }
    }
}
