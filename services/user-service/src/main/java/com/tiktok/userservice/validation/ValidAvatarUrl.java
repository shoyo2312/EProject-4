package com.tiktok.userservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AvatarUrlValidator.class)
public @interface ValidAvatarUrl {

    String message() default "avatarUrl must be an https URL on an allowed host";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
