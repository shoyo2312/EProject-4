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
     * from {@code TokenIssuer}, the one funnel every sign-in path ends in. Not a precise login
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

    public void changePasswordHash(String newHash) {
        this.passwordHash = newHash;
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
     * ACTIVE would silently undo that other decision.
     */
    public void unban() {
        if (this.status == UserStatus.BANNED) {
            this.status = UserStatus.ACTIVE;
            this.bannedAt = null;
            this.bannedUntil = null;
            this.banReason = null;
        }
    }

    /** Advances {@link #lastLoginAt}; see its field doc for why a refresh counts. */
    public void recordLogin() {
        this.lastLoginAt = Instant.now();
    }

    public void promoteToAdmin() {
        this.role = UserRole.ADMIN;
        this.emailVerified = true;
    }

    public void markEmailVerified() {
        this.emailVerified = true;
        this.emailVerifiedAt = Instant.now();
    }
}
