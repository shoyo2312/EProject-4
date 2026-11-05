package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A profile's birth date was set after sign-up (social sign-ins and accounts older than the
 * column), or withdrawn: erasing the account sends {@code birthDate = null}, so analytics drops
 * the date it kept for age groups. Set once, so at most two events per account.
 */
public record UserBirthDateSetEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        LocalDate birthDate
) implements DomainEvent {

    public static UserBirthDateSetEvent of(Long userId, LocalDate birthDate) {
        return new UserBirthDateSetEvent(UUID.randomUUID().toString(), Instant.now(), userId, birthDate);
    }
}
