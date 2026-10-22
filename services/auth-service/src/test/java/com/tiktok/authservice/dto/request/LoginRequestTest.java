package com.tiktok.authservice.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unknown identifier becomes part of a Redis key (LoginRateLimiter). Unbounded, a few hundred
 * megabyte-long ones filled the shared noeviction Redis and broke every service writing to it.
 */
class LoginRequestTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void anIdentifierAsLongAsTheLongestEmail_fits_oneMoreDoesNot() {
        String longest = "a".repeat(255);

        assertThat(validator.validate(new LoginRequest(longest, "secret"))).isEmpty();
        assertThat(validator.validate(new LoginRequest(longest + "a", "secret"))).isNotEmpty();
    }

    @Test
    void theAdminOtpStepHasTheSameBound() {
        String longest = "a".repeat(255);

        assertThat(validator.validate(new AdminLoginOtpRequest(longest, "123456", "t", false))).isEmpty();
        assertThat(validator.validate(new AdminLoginOtpRequest(longest + "a", "123456", "t", false))).isNotEmpty();
    }
}
