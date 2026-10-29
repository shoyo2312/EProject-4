package com.tiktok.chatservice.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Keep the existing raw content cap for TEXT/STICKER; VIDEO is checked after trim in MessagePayload. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MessageContentSizeValidator.class)
public @interface MessageContentSize {
    String message() default "size must be between 0 and 2000";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
