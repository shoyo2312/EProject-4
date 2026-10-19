package com.tiktok.authservice.dto.request;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * At most 72 bytes once encoded as UTF-8, for every password that gets hashed. bcrypt reads no
 * further, so the rest of a longer one never counted, and Spring Security now refuses to hash it
 * at all (CVE-2025-22228) with an IllegalArgumentException that a request turned into a 500.
 * Bytes, not characters: a Vietnamese password reaches 72 well inside its 100-character cap.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FitsBcrypt.Validator.class)
public @interface FitsBcrypt {

    int MAX_BYTES = 72;

    String message() default "must be at most 72 bytes";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<FitsBcrypt, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        }
    }
}
