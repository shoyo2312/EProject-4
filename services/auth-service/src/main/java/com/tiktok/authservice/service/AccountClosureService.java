package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.CloseAccountRequest;
import com.tiktok.authservice.dto.response.DeletionScheduledResponse;
import com.tiktok.authservice.entity.User;

/**
 * The owner closing their own account — deactivating it, or asking for it to be deleted after a
 * grace period — and reopening it by signing in again. See the account-deactivation design.
 *
 * <p>Every change here queues an {@code AccountStateChangedEvent} in the same transaction, which
 * the services that show the account's content follow to hide or show it.
 */
public interface AccountClosureService {

    /** Mails the code an account with no password reconfirms with. Its address must be verified. */
    void sendClosureCode(Long userId);

    /** Hides the account until its owner signs in again. Every session ends. */
    void deactivate(Long userId, CloseAccountRequest request);

    /** {@link #deactivate}, with the account deleted once the grace period passes. */
    DeletionScheduledResponse requestDeletion(Long userId, CloseAccountRequest request);

    /**
     * Where a sign-in whose credential checked out goes on from. An open account passes as it is.
     * A closed one is refused with what happened, unless the caller asked to reopen it, which is
     * done here.
     *
     * @return the account to issue the session for, reopened if it was
     */
    User admit(User user, boolean reactivate);

    /** Runs a deletion that has come due. A no-op for an account reopened in the meantime. */
    void eraseIfDue(Long userId);
}
