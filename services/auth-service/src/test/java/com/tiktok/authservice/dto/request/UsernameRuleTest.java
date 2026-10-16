package com.tiktok.authservice.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two signups hold a username to the rule a rename does — see {@link UsernameRule}. They used
 * to take any 3-50 characters, so "victim@example.com" was a username anyone could register.
 */
class UsernameRuleTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @ParameterizedTest
    @ValueSource(strings = {"victim@example.com", "Alice", "1abc", "ab", "abc-def"})
    void bothSignups_refuseWhatARenameRefuses(String username) {
        assertThat(usernameViolations(validator.validate(
                new RegisterRequest(username, "someone@example.com", "password123", null)))).isNotEmpty();
        assertThat(usernameViolations(validator.validate(
                new CompleteSignupRequest("ticket", username, "password123")))).isNotEmpty();
    }

    @Test
    void bothSignups_acceptAValidHandle() {
        assertThat(validator.validate(
                new RegisterRequest("minh_hung", "someone@example.com", "password123", null))).isEmpty();
        assertThat(validator.validate(
                new CompleteSignupRequest("ticket", "minh_hung", "password123"))).isEmpty();
    }

    private static <T> Set<ConstraintViolation<T>> usernameViolations(Set<ConstraintViolation<T>> violations) {
        violations.removeIf(v -> !v.getPropertyPath().toString().equals("username"));
        return violations;
    }
}
