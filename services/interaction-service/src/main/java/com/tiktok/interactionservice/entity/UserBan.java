package com.tiktok.interactionservice.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.cassandra.core.mapping.Column;
import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;

/**
 * interaction-service's own answer to "is this author banned", which every comment listing asks: a
 * ban used to end the account's sessions and hide its videos, and its comments stayed up under
 * everyone else's. Written only by AdminModerationEventConsumer; the same record video-service keeps
 * as {@code owner_bans}.
 *
 * <p>Read at query time rather than stamped on the comments, because a ban carries its own end and
 * nothing announces it — auth-service's ExpiredBanSweep lifts a temporary ban quietly — so a flag on
 * the comments would need something to clear it. A ban is in force while {@code bannedUntil} is in
 * the future; an author with no row, a null, or a date gone by is not banned.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_bans")
public class UserBan {

    /** A ban with no end, stored as a date every read compares past now. */
    public static final Instant PERMANENT = Instant.parse("9999-12-31T23:59:59Z");

    @PrimaryKey("user_id")
    private Long userId;

    /** Null once lifted. */
    @Column("banned_until")
    private Instant bannedUntil;

    /** When admin-service decided: an older decision arriving later must not undo a newer one. */
    @Column("changed_at")
    private Instant changedAt;

    /**
     * When the author closed their own account; null while it is open. Independent of the ban, with
     * its own guard, so an unban never reopens a closed account and a reopening never lifts a ban.
     * Written only by AccountStateEventConsumer.
     */
    @Column("deactivated_at")
    private Instant deactivatedAt;

    @Column("account_state_changed_at")
    private Instant accountStateChangedAt;

    public boolean isInForce(Instant now) {
        return bannedUntil != null && bannedUntil.isAfter(now);
    }

    /** Banned, or closed by its owner: either way the author's comments leave every listing. */
    public boolean hides(Instant now) {
        return isInForce(now) || deactivatedAt != null;
    }
}
