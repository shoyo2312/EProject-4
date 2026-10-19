package com.tiktok.authservice.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Security refuses to hash a password longer than 72 bytes (CVE-2025-22228: bcrypt reads no
 * further, so the rest never counted), and the refusal is an IllegalArgumentException — a 500 —
 * for anything that reached it. The limit is bytes, not characters: a Vietnamese password reaches
 * it well inside the 100-character cap.
 */
class FitsBcryptTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void seventyTwoBytes_fit_seventyThreeDoNot() {
        assertThat(validator.validate(register("a".repeat(72)))).isEmpty();
        assertThat(validator.validate(register("a".repeat(73)))).isNotEmpty();
        assertThat(validator.validate(new ResetPasswordRequest("a@b.co", "123456", "a".repeat(73)))).isNotEmpty();
        assertThat(validator.validate(new CompleteSignupRequest("ticket", "minh_hung", "a".repeat(73)))).isNotEmpty();
    }

    @Test
    void theLimitIsBytes_notCharacters() {
        String thirtyCharsNinetyBytes = "ẫ".repeat(30);

        assertThat(validator.validate(register(thirtyCharsNinetyBytes))).isNotEmpty();
    }

    private static RegisterRequest register(String password) {
        return new RegisterRequest("minh_hung", "someone@example.com", password, null);
    }
}
