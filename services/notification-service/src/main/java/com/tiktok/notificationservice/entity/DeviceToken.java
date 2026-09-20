package com.tiktok.notificationservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * An FCM registration token, without which nothing can be pushed anywhere — the push service has
 * always taken a token and nobody stored one.
 *
 * <p>The token is the {@code _id} rather than a field with a unique index on it. A device
 * re-registering is then an ordinary {@code save} that overwrites itself, and the same token
 * moving to a second account on a shared phone reassigns rather than duplicating; with a
 * generated id both cases need a read-then-write that two concurrent registrations can race.
 *
 * <p>Infrastructure, not a business entity: no {@code BaseEntity}, no soft delete. Logging out
 * removes the row, because a token left behind keeps pushing to a device nobody is signed in on.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "device_tokens")
public class DeviceToken {

    @Id
    private String token;

    @Indexed
    private Long userId;

    @CreatedDate
    private Instant createdAt;
}
