package com.tiktok.adminservice.entity;

import com.tiktok.adminservice.exception.InvalidModerationTargetException;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * What a report or a moderation action points at, and — because each kind of target is
 * identified differently — how to tell a usable {@code targetId} from an unusable one, and how to
 * spell a usable one.
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
        public String canonicalTargetId(String targetId) {
            return Long.toString(positiveLong(targetId,
                    "USER targetId must be a positive numeric user id, got: " + targetId));
        }
    },

    /**
     * A video id. video-service mints Snowflakes but keys its documents on the string, so nothing
     * beyond "present" is required of one; a numeric one is still spelled the way video-service
     * spells it.
     */
    VIDEO {
        @Override
        public String canonicalTargetId(String targetId) {
            if (targetId == null || targetId.isBlank()) {
                throw new InvalidModerationTargetException("VIDEO targetId must not be blank");
            }
            String id = targetId.strip();
            return UNSIGNED_DECIMAL.matcher(id).matches() ? new BigInteger(id).toString() : id;
        }
    },

    /** The {@code "videoId:commentId"} pair — see {@link CommentTarget} for why one id is not enough. */
    COMMENT {
        @Override
        public String canonicalTargetId(String targetId) {
            try {
                return CommentTarget.parse(targetId == null ? null : targetId.strip()).targetId();
            } catch (IllegalArgumentException e) {
                throw new InvalidModerationTargetException(e.getMessage());
            }
        }
    };

    private static final Pattern UNSIGNED_DECIMAL = Pattern.compile("\\+?[0-9]+");

    /**
     * The one spelling of this target's id that the service stores and compares. Reports are
     * deduplicated, closed together, held against a standing enforcement and counted as strikes by
     * plain string equality, so {@code "007"}, {@code "+7"} and {@code " 7"} have to become one id
     * here — otherwise each is a target of its own, with its own queue row and its own history, and
     * the self-ban guard does not recognise the admin's own id in any of them.
     *
     * @throws InvalidModerationTargetException if this type cannot act on that id
     */
    public abstract String canonicalTargetId(String targetId);

    static long positiveLong(String targetId, String message) {
        if (targetId != null) {
            try {
                long id = Long.parseLong(targetId.strip());
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException e) {
                // Falls through to the same refusal as a non-positive id.
            }
        }
        throw new InvalidModerationTargetException(message);
    }
}
