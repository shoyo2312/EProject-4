package com.tiktok.authservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One entry in an account's security log.
 *
 * <p>Infrastructure row like {@link RefreshToken}: written once, never edited, no
 * {@code BaseEntity} and no soft delete, hard-deleted by {@code ExpiredRecordCleanup} past the
 * retention window.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "auth_security_events")
public class AuthSecurityEvent {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private SecurityEventType eventType;

    /**
     * The address api-gateway resolved for the request, or null when there was no request behind
     * the event — a scheduled sweep, or a Kafka consumer acting on the account.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** {@code "Chrome on macOS"} — derived from the agent, so a list is readable at a glance. */
    @Column(name = "device_label", length = 128)
    private String deviceLabel;

    /** A short phrase: the new address, why sessions were revoked. Never a secret. */
    @Column(name = "detail", length = 255)
    private String detail;

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
