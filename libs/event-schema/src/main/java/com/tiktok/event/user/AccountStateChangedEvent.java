package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The owner deactivated their account, asked for it to be deleted, reopened it by signing in, or
 * the deletion they asked for ran. auth-service owns the state; every service that shows the
 * account's content keeps its own copy and hides the owner while it is not ACTIVE.
 *
 * <p>Its own topic, {@code auth.account-events}, for the reason {@link UsernameChangedEvent} has
 * one. Consumers apply ACTIVE and DEACTIVATED as an assignment guarded by {@code occurredAt}, so a
 * redelivery or a late re-send never undoes a newer change. DELETED is a fan-out, not one
 * assignment, and is claimed by {@code eventId} where it does more than hide.
 *
 * <p>Independent of a ban on purpose: an unban must not reopen an account its owner closed, and
 * reopening one must not lift a ban.
 *
 * @param deletionScheduledAt when the account will be deleted; set only with DEACTIVATED after a
 *                            deletion request, null otherwise
 */
public record AccountStateChangedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        AccountState state,
        Instant deletionScheduledAt
) implements DomainEvent {

    public static AccountStateChangedEvent of(Long userId, AccountState state, Instant deletionScheduledAt) {
        return new AccountStateChangedEvent(
                UUID.randomUUID().toString(), Instant.now(), userId, state, deletionScheduledAt);
    }
}
