package com.tiktok.common.validation;

import java.time.LocalDate;

/**
 * The one age gate, shared by sign-up (auth-service) and the later "set your birthday" step
 * (user-service): at least 13 on the day it is checked, not in the future, not before 1900. The
 * day is passed in so callers decide the zone and tests pin it.
 */
public final class BirthDateRule {

    public static final int MINIMUM_AGE_YEARS = 13;
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    private BirthDateRule() {
    }

    public static boolean isAllowed(LocalDate birthDate, LocalDate today) {
        return birthDate != null
                && !birthDate.isBefore(EARLIEST)
                && !birthDate.isAfter(today.minusYears(MINIMUM_AGE_YEARS));
    }
}
