package com.tiktok.authservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A browser an ADMIN ticked "remember this device" on, letting admin login skip the email-OTP
 * step until {@link #expiresAt}. Infrastructure row like {@link RefreshToken} — not a domain
 * entity, no {@code BaseEntity}, hard-deleted by {@code ExpiredRecordCleanup} once expired.
 *
 * <p>Only {@code tokenHash} (SHA-256 of a 256-bit random token) is stored; the raw token lives in
 * an httpOnly cookie on the console and is never persisted here.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "remembered_devices")
public class RememberedDevice {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = SnowflakeIdGenerator.nextId();
        }
        createdAt = Instant.now();
    }
}
