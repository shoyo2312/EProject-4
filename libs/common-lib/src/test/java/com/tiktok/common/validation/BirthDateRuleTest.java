package com.tiktok.common.validation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthDateRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    @Test
    void exactlyThirteenTodayIsAllowed() {
        assertThat(BirthDateRule.isAllowed(LocalDate.of(2013, 11, 5), TODAY)).isTrue();
    }

    @Test
    void oneDayShortOfThirteenIsNot() {
        assertThat(BirthDateRule.isAllowed(LocalDate.of(2013, 11, 6), TODAY)).isFalse();
    }

    @Test
    void before1900IsNot() {
        assertThat(BirthDateRule.isAllowed(LocalDate.of(1899, 12, 31), TODAY)).isFalse();
        assertThat(BirthDateRule.isAllowed(LocalDate.of(1900, 1, 1), TODAY)).isTrue();
    }

    @Test
    void tomorrowIsNot() {
        assertThat(BirthDateRule.isAllowed(TODAY.plusDays(1), TODAY)).isFalse();
    }

    @Test
    void nullIsNot() {
        assertThat(BirthDateRule.isAllowed(null, TODAY)).isFalse();
    }
}
