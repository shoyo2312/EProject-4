package com.tiktok.moderation.text;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A length limit counted in code points rather than {@code @Size}'s UTF-16 units — the count a
 * reader would make, and the one a JavaScript client gets from {@code [...text].length}. Under
 * {@code @Size} an emoji costs two, so "150 characters" meant 75 for a comment written in emoji.
 *
 * <p>Pair it with {@link TextSanitizer} in the record's compact constructor, so what is counted is
 * what is stored: decomposed Vietnamese is composed first, and invisible characters count nothing
 * because they are gone. A null value passes — pair it with {@code @NotBlank} where the field is
 * required.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxCodePointsValidator.class)
public @interface MaxCodePoints {

    /** The most code points the value may have. */
    int value();

    String message() default "must be at most {value} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
