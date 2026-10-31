package com.tiktok.storyservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * story-service's own answer to "is this owner hidden", the same record video-service keeps: an
 * owner is hidden while banned and while they have closed their own account. Read at query time,
 * never stamped on stories, because a temporary ban ends without any event announcing it.
 *
 * <p>The ban and the closure are two independent pairs of fields, each with its own guard, so an
 * unban never reopens an account its owner closed and a reopening never lifts a ban. The ban pair
 * is written only by AdminModerationEventConsumer, the closure pair only by
 * AccountStateEventConsumer.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "owner_bans")
public class OwnerBan {

    /** A ban with no end, stored as a date every read compares past now. */
    public static final Instant PERMANENT = Instant.parse("9999-12-31T23:59:59Z");

    @Id
    private Long userId;

    /** Null once lifted. A ban is in force while this is in the future. */
    @Indexed
    private Instant bannedUntil;

    /** When admin-service decided: an older decision arriving later must not undo a newer one. */
    private Instant changedAt;

    /** When the owner closed the account; null while it is open. */
    @Indexed(sparse = true)
    private Instant deactivatedAt;

    /** The guard for {@link #deactivatedAt}, separate from {@link #changedAt}. */
    private Instant accountStateChangedAt;

    public OwnerBan(Long userId, Instant bannedUntil, Instant changedAt) {
        this(userId, bannedUntil, changedAt, null, null);
    }
}
