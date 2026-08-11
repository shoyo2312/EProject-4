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
     * Set only when this token was retired by rotation at /refresh — never by logout or a
     * password reset. That distinction is what makes replay detection possible: a rotated token
     * has a live successor somewhere, so seeing it a second time means two parties hold the same
     * chain. A logged-out token has no successor, and replaying it is just a stale client.
     *
     * <p>Written exclusively by {@code RefreshTokenRepository.claimForRotation}, never by a
     * setter here: whether this column is still null is the race the rotation has to win, so the
     * read and the write cannot be two statements.
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

    public boolean isExpired() {
        return !expiresAt.isAfter(Instant.now());
    }

    /**
     * True once a successor has been handed out for this token. Presenting it after that means
     * two parties hold the same chain, and the server cannot tell which one is legitimate.
     */
    public boolean isRotated() {
        return rotatedAt != null;
    }

    /**
     * Whether the successor was handed out long enough ago that an in-flight retry from the
     * legitimate client cannot explain this token coming back — see the grace window in
     * {@code AuthServiceImpl.rejectAsReplay}.
     */
    public boolean wasRotatedBefore(Instant cutoff) {
        return rotatedAt != null && rotatedAt.isBefore(cutoff);
    }
}
