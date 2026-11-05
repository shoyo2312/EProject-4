package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A profile's birth date was set after sign-up (social sign-ins and accounts older than the
 * column). It is set once, so one event per account; analytics keeps it for age groups.
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
