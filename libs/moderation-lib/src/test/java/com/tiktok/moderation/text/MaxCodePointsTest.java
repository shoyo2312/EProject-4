package com.tiktok.moderation.text;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MaxCodePointsTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    record Comment(@MaxCodePoints(5) String content, List<@MaxCodePoints(3) String> tags) {
    }

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** Five emoji are ten UTF-16 units — @Size(max = 5) would refuse them. */
    @Test
    void countsCodePoints_notUtf16Units() {
        assertThat(validator.validate(new Comment("🔥🔥🔥🔥🔥", null))).isEmpty();
        assertThat(validator.validate(new Comment("🔥🔥🔥🔥🔥🔥", null))).hasSize(1);
    }

    @Test
    void null_passes() {
        assertThat(validator.validate(new Comment(null, null))).isEmpty();
    }

    @Test
    void appliesToListElements_andSaysHowLong() {
        Set<ConstraintViolation<Comment>> violations = validator.validate(new Comment("ok", List.of("ab", "abcd")));

        assertThat(violations).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("must be at most 3 characters");
    }
}
