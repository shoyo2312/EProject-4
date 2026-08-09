package com.tiktok.authservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * Set only when this token was retired by {@link #rotate()} at /refresh — never by logout or
     * a password reset. That distinction is what makes replay detection possible: a rotated token
     * has a live successor somewhere, so seeing it a second time means two parties hold the same
     * chain. A logged-out token has no successor, and replaying it is just a stale client.
     */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = SnowflakeIdGenerator.nextId();
        }
        createdAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }

    public void revoke() {
        this.revokedAt = Instant.now();
    }

    /** Retires this token because /refresh issued a successor for it. */
    public void rotate() {
        Instant now = Instant.now();
        this.revokedAt = now;
        this.rotatedAt = now;
    }

    /**
     * True when this token was rotated away but its natural expiry has not passed yet — i.e. it
     * is being presented after its successor was already handed out. Whoever holds it is one of
     * two parties on the same chain, and the server cannot tell which is the legitimate one.
     */
    public boolean isReplayOfRotatedToken() {
        return rotatedAt != null && expiresAt.isAfter(Instant.now());
    }
}
