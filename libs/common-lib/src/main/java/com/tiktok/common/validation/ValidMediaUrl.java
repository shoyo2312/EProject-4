package com.tiktok.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Accepts only media URLs that point at storage we own — see {@link MediaUrlValidator}.
 *
 * <p>Put this on every URL a <em>client</em> sends us (avatar, product image, raw upload). URLs
 * the system produces for itself — a thumbnail path built from the MinIO endpoint, a URL echoed
 * back from another service — need no annotation: they are safe by construction, and validating
 * them only invites someone to relax the rule to make an internal path fit.
 *
 * <p>The scheme list lives here, at the field, because it differs per field and reviewers should
 * see it next to what it guards. The host/bucket allow-list lives in configuration
 * ({@link MediaUrlProperties}) because it is the same system-wide and has to change at deploy
 * time without a rebuild.
 *
 * <p>A blank or null value passes — this constraint answers "is this URL acceptable", not "is
 * this field required". Pair it with {@code @NotBlank} where the field is mandatory.
 *
 * <p>The service using it must register the properties:
 * {@code @EnableConfigurationProperties(MediaUrlProperties.class)}. Without that the validator
 * cannot be constructed and requests fail loudly rather than skipping the check.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MediaUrlValidator.class)
public @interface ValidMediaUrl {

    /** URI schemes accepted for this field, matched case-insensitively. */
    String[] schemes() default {"https"};

    String message() default "must be a URL on an allowed media host";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
