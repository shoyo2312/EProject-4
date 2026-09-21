package com.tiktok.adminservice.entity;

import com.tiktok.adminservice.exception.InvalidModerationTargetException;

import java.util.Arrays;
import java.util.List;

/**
 * What an admin decided, which kind of target the decision makes sense against, and what the
 * decision does to the target's standing state.
 *
 * <p>The pairing is enforced rather than assumed because resolving a report carries the report's
 * targetType and the admin's chosen actionType separately, and nothing in the request ties them
 * together. BAN_USER against a VIDEO report published a UserBannedEvent holding a Mongo document
 * id where a user id belongs — no exception on the consumer side, no log, just a ban aimed at
 * nobody; TAKEDOWN_VIDEO against a USER report is the same mistake pointing the other way.
 */
public enum ModerationActionType {

    BAN_USER(ReportTargetType.USER, Effect.ENFORCE),
    UNBAN_USER(ReportTargetType.USER, Effect.REVERSE),
    TAKEDOWN_VIDEO(ReportTargetType.VIDEO, Effect.ENFORCE),
    RESTORE_VIDEO(ReportTargetType.VIDEO, Effect.REVERSE),
    /** No reversal exists: interaction-service removes the comment row, it does not hide it. */
    REMOVE_COMMENT(ReportTargetType.COMMENT, Effect.ENFORCE),
    /** Audit-only, and an admin may warn over any kind of report, so nothing to pin it to. */
    WARN_USER(null, Effect.NONE),
    /** Audit-only: closing a report without acting says nothing about what it pointed at. */
    DISMISS_REPORT(null, Effect.NONE);

    /** What the action does to whether the target is currently under enforcement. */
    public enum Effect {
        /** The target is now removed, banned or taken down. */
        ENFORCE,
        /** An earlier ENFORCE against the same target was undone. */
        REVERSE,
        /** Recorded for the audit log; the target's state is unchanged either way. */
        NONE
    }

    /**
     * The actions that move a target in or out of enforcement, and so the only ones worth reading
     * when asking "is this already dealt with". A WARN_USER or DISMISS_REPORT in between says
     * nothing about that, and treating it as the latest word would answer "no" for a video that
     * is still down.
     */
    public static final List<ModerationActionType> STATE_CHANGING = Arrays.stream(values())
            .filter(type -> type.effect != Effect.NONE)
            .toList();

    private final ReportTargetType appliesTo;
    private final Effect effect;

    ModerationActionType(ReportTargetType appliesTo, Effect effect) {
        this.appliesTo = appliesTo;
        this.effect = effect;
    }

    public Effect effect() {
        return effect;
    }

    /** @throws InvalidModerationTargetException if this action cannot be taken against that target. */
    public void requireApplicableTo(ReportTargetType targetType) {
        if (appliesTo != null && appliesTo != targetType) {
            throw new InvalidModerationTargetException(
                    this + " applies to a " + appliesTo + " target, but the target is a " + targetType);
        }
    }

    /**
     * What this decision makes of the reports standing against the same target.
     *
     * <p>A reversal dismisses them rather than resolving them: restoring a video or unbanning an
     * account is a finding that the reports were wrong, and RESOLVED would record the opposite in
     * the one place anyone looks back at.
     */
    public ReportStatus resolutionStatus() {
        return switch (effect) {
            case ENFORCE -> ReportStatus.RESOLVED;
            case REVERSE -> ReportStatus.DISMISSED;
            // WARN_USER acted on the report; DISMISS_REPORT declined to.
            case NONE -> this == DISMISS_REPORT ? ReportStatus.DISMISSED : ReportStatus.RESOLVED;
        };
    }
}
