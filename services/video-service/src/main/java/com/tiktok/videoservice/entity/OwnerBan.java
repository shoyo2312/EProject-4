package com.tiktok.videoservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * video-service's own answer to "is this owner hidden", which every viewer-facing read asks: a ban
 * used to end the account's sessions and nothing else, and its videos went on playing. An owner is
 * hidden while banned, and while they have closed their own account.
 *
 * <p>Read at query time rather than stamped on the videos, because a ban carries its own end and
 * nothing announces it — auth-service's ExpiredBanSweep lifts a temporary ban quietly — so a flag
 * on the videos would need something to clear it. A ban is in force while {@code bannedUntil} is
 * in the future; an owner with no document, a null, or a date gone by is not banned.
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

    /** Null once lifted. Indexed for the public feed, which asks for every ban in force. */
    @Indexed
    private Instant bannedUntil;

    /** When admin-service decided: an older decision arriving later must not undo a newer one. */
    private Instant changedAt;

    /** When the owner closed the account; null while it is open. Indexed for the public feed too. */
    @Indexed(sparse = true)
    private Instant deactivatedAt;

    /** The guard for {@link #deactivatedAt}, separate from {@link #changedAt}. */
    private Instant accountStateChangedAt;

    public OwnerBan(Long userId, Instant bannedUntil, Instant changedAt) {
        this(userId, bannedUntil, changedAt, null, null);
    }
}
