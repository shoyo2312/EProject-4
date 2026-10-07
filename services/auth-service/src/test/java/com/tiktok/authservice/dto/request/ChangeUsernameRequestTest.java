package com.tiktok.authservice.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The web client's username rule, which this endpoint is the first to enforce server-side. */
class ChangeUsernameRequestTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "minh_hung", "a.b_c9", "user1u5baa"})
    void lowercaseLettersDigitsUnderscoresAndPeriods_startingWithALetter_pass(String username) {
        assertThat(validator.validate(new ChangeUsernameRequest(username))).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ab", "Alice", "1abc", "_abc", ".abc", "ab c", "a@b.c", "abc-def", "  "})
    void anythingElse_isRefused(String username) {
        assertThat(validator.validate(new ChangeUsernameRequest(username))).isNotEmpty();
    }

    @Test
    void fiftyCharactersFit_fiftyOneDoNot() {
        String fifty = "a".repeat(50);

        assertThat(validator.validate(new ChangeUsernameRequest(fifty))).isEmpty();
        assertThat(validator.validate(new ChangeUsernameRequest(fifty + "a"))).isNotEmpty();
    }
}
