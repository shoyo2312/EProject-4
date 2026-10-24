package com.tiktok.authservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A device an account has signed in or refreshed a session from, by the id the client keeps
 * ({@code X-Device-Id}). A sign-in from a device not on this list tells the owner.
 *
 * <p>Infrastructure row like {@link RememberedDevice} — not a domain entity, no {@code BaseEntity},
 * hard-deleted: evicted past 50 per account, forgotten once unused for the security log's
 * retention, removed when the account is erased. Only {@code deviceHash} (SHA-256 of the id) is
 * stored. Every write is a native query in {@code KnownDeviceRepository}, because what a sign-in
 * needs to know — new or not — is the row count of the insert itself.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "known_devices")
public class KnownDevice {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_hash", nullable = false, length = 64)
    private String deviceHash;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
