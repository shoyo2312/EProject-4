package com.tiktok.userservice.service;

import java.time.Instant;

/**
 * Follows auth-service's account state — the owner closing the account, reopening it, or its
 * deletion running. See the account-deactivation design.
 */
public interface AccountStateService {

    /**
     * Hides the profile from everyone else, or shows it again. Edges and counters stay. A no-op
     * when a change newer than {@code changedAt} is already recorded.
     */
    void applyDeactivated(Long userId, boolean deactivated, Instant changedAt);

    /**
     * The deletion ran: every follow edge in both directions ends, with the other side's counter
     * and an announcement for each; pending requests, blocks, mutes and settings end too; and the
     * profile is soft-deleted. Not idempotent on its own — the caller claims the event first.
     */
    void erase(Long userId, Instant changedAt);
}
