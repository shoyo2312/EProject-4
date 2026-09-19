package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.common.exception.DomainException;
import com.tiktok.common.response.ApiResponse;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
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

    /**
     * A refusal from the service — a block, a conversation the sender is not in — used to die in
     * the broker's log, and the sender's message simply never appeared with nothing saying why.
     */
    @Test
    void domainRefusals_goBackToTheSendersErrorQueue() throws NoSuchMethodException {
        Method handler = ChatWebSocketController.class.getMethod("handleRefusal", DomainException.class);

        assertThat(handler.getAnnotation(MessageExceptionHandler.class)).isNotNull();
        assertThat(handler.getAnnotation(SendToUser.class).value()).containsExactly("/queue/errors");

        ApiResponse<Void> body = new ChatWebSocketController(null).handleRefusal(new MessagingBlockedException());
        assertThat(body.code()).isEqualTo("MESSAGING_BLOCKED");
    }
}
