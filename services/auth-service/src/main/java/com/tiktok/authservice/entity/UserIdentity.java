package com.tiktok.authservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * The link between one of our accounts and one account at an external provider.
 *
 * <p>A row here is what makes a returning social login a login rather than a signup: the provider
 * uid is looked up first, before anything is inferred from the email address, so an account that
 * has signed in once keeps resolving to itself even if its address later changes hands at the
 * provider.
 *
 * <p>Infrastructure, like {@link RefreshToken}: no BaseEntity, no soft delete. Unlinking a
 * provider means the link is gone, and a tombstone would only keep {@code uq_identity} occupied
 * against the day the same provider account is linked again.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_identities")
public class UserIdentity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false)
    private AuthProvider provider;

    /** The provider's own immutable id for the account — Google's {@code sub}, Facebook's id. */
    @Column(name = "provider_uid", nullable = false, updatable = false)
    private String providerUid;

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
