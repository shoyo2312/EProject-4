package com.tiktok.adminservice.entity;

import com.tiktok.adminservice.exception.InvalidModerationTargetException;

/**
 * What a report or a moderation action points at, and — because each kind of target is
 * identified differently — how to tell a usable {@code targetId} from an unusable one.
 *
 * <p>The check belongs here rather than at the edge because the id outlives the request that
 * submitted it: an unparseable one is accepted quietly, sits in the reports table until an admin
 * resolves it days later, and only then fails, inside the transaction that writes the audit row
 * and the outbox event. The report is then stuck PENDING with no route out of it.
 */
public enum ReportTargetType {

    /** A Snowflake user id, the form auth-service and user-service both key on. */
    USER {
        @Override
        public void validateTargetId(String targetId) {
            requireLong(targetId, "USER targetId must be a numeric user id, got: " + targetId);
        }
    },

    /** A Mongo document id — a string, not a Snowflake, so nothing beyond "present" can be checked. */
    VIDEO {
        @Override
        public void validateTargetId(String targetId) {
            if (targetId == null || targetId.isBlank()) {
                throw new InvalidModerationTargetException("VIDEO targetId must not be blank");
            }
        }
    },

    /** The {@code "videoId:commentId"} pair — see {@link CommentTarget} for why one id is not enough. */
    COMMENT {
        @Override
        public void validateTargetId(String targetId) {
            try {
                CommentTarget.parse(targetId);
            } catch (IllegalArgumentException e) {
                throw new InvalidModerationTargetException(e.getMessage());
            }
        }
    };

    /** @throws InvalidModerationTargetException if this type cannot act on that id. */
    public abstract void validateTargetId(String targetId);

    static void requireLong(String targetId, String message) {
        try {
            Long.valueOf(targetId);
        } catch (NumberFormatException e) {
            throw new InvalidModerationTargetException(message);
        }
    }
}
