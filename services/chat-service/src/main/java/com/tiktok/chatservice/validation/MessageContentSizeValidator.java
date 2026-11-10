package com.tiktok.chatservice.validation;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.entity.MessageType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class MessageContentSizeValidator implements ConstraintValidator<MessageContentSize, SendMessageRequest> {
    @Override
    public boolean isValid(SendMessageRequest request, ConstraintValidatorContext context) {
        if (request == null || request.type() == MessageType.VIDEO || request.content() == null
                || request.content().length() <= 2000) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("content").addConstraintViolation();
        return false;
    }
}
