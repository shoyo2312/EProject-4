package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The socket path and the REST path share one DTO, and only the REST path used to enforce it:
 * STOMP payload binding does not validate unless the parameter says so, so blank or oversized
 * content sent over the socket was stored and broadcast.
 */
class ChatWebSocketControllerTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void sendPayload_isMarkedForValidation() throws NoSuchMethodException {
        Method send = ChatWebSocketController.class.getMethod(
                "send", String.class, SendMessageRequest.class, Principal.class);

        boolean validated = Arrays.stream(send.getParameterAnnotations()[1])
                .anyMatch(annotation -> annotation instanceof Valid);

        assertThat(validated)
                .as("@Valid on the STOMP payload — without it the constraints below are never run")
                .isTrue();
    }

    @Test
    void blankAndOversizedContent_breakTheConstraintsTheAnnotationRuns() {
        assertThat(validator.validate(new SendMessageRequest("  "))).isNotEmpty();
        assertThat(validator.validate(new SendMessageRequest("x".repeat(2001)))).isNotEmpty();
        assertThat(validator.validate(new SendMessageRequest("hello"))).isEmpty();
    }
}
