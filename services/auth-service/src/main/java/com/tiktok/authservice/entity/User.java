package com.tiktok.authservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    /**
     * When the owner last chose a new handle; null until they first do. The handle picked at
     * signup, or invented for a social one, is not a change, so it does not start the 30 days —
     * see {@code UsernameCooldown}.
     */
    @Column(name = "username_changed_at")
    private Instant usernameChangedAt;

    /**
     * Null for an account created by a social login whose provider gave no address — Facebook's
     * {@code email} permission is optional, and an account registered with a phone number has none
     * to give. Such an account cannot reset a password or receive mail until it supplies one, so
     * the client is told to ask; see {@code OAuthService}.
     */
    @Column(name = "email", unique = true)
    private String email;

    /** Null for an account that has only ever signed in through a provider and set no password. */
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Builder.Default
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /**
     * Last time a token pair was issued for this account — a fresh sign-in or a refresh. Advanced
     * from {@code TokenIssuer}, the one funnel every sign-in path ends in, by a bulk update that
     * leaves {@code version} alone ({@code UserRepository.stampLastLogin}). Not a precise login
     * clock (a background refresh moves it too); enough to tell a dormant account from a live one.
     */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** When the current ban was applied; null unless {@code status == BANNED}. Cleared on unban. */
    @Column(name = "banned_at")
    private Instant bannedAt;

    /**
     * When the current ban lapses; null for a permanent one. A lapsed ban is lifted by
     * {@code ExpiredBanSweep} rather than by whoever next reads this row — the account's standing
     * has to be the same answer for the directory, the sign-in path and the profile.
     */
    @Column(name = "banned_until")
    private Instant bannedUntil;

    /**
     * Reason carried on the {@code UserBannedEvent} that banned this account, kept here so the
     * admin console shows it without reading admin-service's audit log. Cleared on unban.
     */
    @Column(name = "ban_reason")
    private String banReason;

    /**
     * When the owner closed the account; null while it is open. Kept under a ban laid on top, so
     * that {@link #unban} returns the account to DEACTIVATED rather than reopening it.
     */
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    /** When the deletion the owner asked for runs; null when they only paused the account. */
    @Column(name = "deletion_scheduled_at")
    private Instant deletionScheduledAt;

    /**
     * Claims an address for an account that has none. Verified state is cleared rather than left
     * alone: the address is unproven until its OTP comes back, and an account that could set
     * {@code email} while {@code emailVerified} stayed true would be able to receive password
     * resets for an address nobody proved it owns.
     */
    public void changeEmail(String newEmail) {
        this.email = newEmail;
        this.emailVerified = false;
        this.emailVerifiedAt = null;
    }

    public void changeUsername(String newUsername, Instant at) {
        this.username = newUsername;
        this.usernameChangedAt = at;
    }

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    /**
     * Leaves the account with no password, as a social signup starts out: signing in by password
     * is impossible until the owner sets one through the reset flow.
     */
    public void clearPassword() {
        this.passwordHash = null;
    }

    public void lock() {
        this.status = UserStatus.LOCKED;
    }

    /** @param bannedUntil when the ban lapses, or null for one that does not. */
    public void ban(String reason, Instant bannedUntil) {
        this.status = UserStatus.BANNED;
        this.bannedAt = Instant.now();
        this.bannedUntil = bannedUntil;
        this.banReason = reason;
    }

    /**
     * Only lifts a ban. LOCKED comes from somewhere else entirely, and an unban that reset it to
     * ACTIVE would silently undo that other decision. The same goes for an account its owner had
     * closed before the ban: it goes back to DEACTIVATED.
     */
    public void unban() {
        if (this.status == UserStatus.BANNED) {
            this.status = deactivatedAt != null ? UserStatus.DEACTIVATED : UserStatus.ACTIVE;
            this.bannedAt = null;
            this.bannedUntil = null;
            this.banReason = null;
        }
    }

    /** @param deletionScheduledAt when to delete it, or null to only pause it */
    public void deactivate(Instant at, Instant deletionScheduledAt) {
        this.status = UserStatus.DEACTIVATED;
        this.deactivatedAt = at;
        this.deletionScheduledAt = deletionScheduledAt;
    }

    /** The owner signed in again: open, and any scheduled deletion cancelled. */
    public void reactivate() {
        this.status = UserStatus.ACTIVE;
        this.deactivatedAt = null;
        this.deletionScheduledAt = null;
    }

    /**
     * The scheduled deletion ran. The row stays, soft-deleted, but nothing on it can sign in or
     * hold a name: the partial unique indexes skip deleted rows, so the username and address are
     * free to be taken again, and the address and password are gone with the row's last use.
     * Provider links are deleted by the caller — their index is not partial.
     */
    public void erase() {
        this.email = null;
        this.emailVerified = false;
        this.emailVerifiedAt = null;
        this.passwordHash = null;
        markDeleted();
    }

    public void promoteToAdmin() {
        this.role = UserRole.ADMIN;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
        this.emailVerifiedAt = Instant.now();
    }
}
