package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Lets a connected client send a message over the socket itself, as an alternative to the
 * REST POST. Both paths funnel through MessageService, so persistence and broadcast happen
 * exactly the same way regardless of which one a client uses.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final MessageService messageService;

    /**
     * {@code @Valid} is not optional here. STOMP payload binding does not validate on its own,
     * so without it the same DTO the REST path rejects — blank content, or content past the 2000
     * character cap — would be stored and broadcast when it arrives over the socket instead.
     */
    @MessageMapping("/chat.send/{conversationId}")
    public void send(@DestinationVariable String conversationId, @Valid @Payload SendMessageRequest request, Principal principal) {
        Long senderId = Long.valueOf(principal.getName());
        messageService.sendMessage(conversationId, senderId, request);
    }

    /**
     * Without a handler the rejection is logged server-side and nothing reaches the client, which
     * sees its message silently vanish. This sends the reason back on the sender's own queue.
     */
    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser("/queue/errors")
    public ApiResponse<Void> handleInvalidPayload(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult() == null ? "Validation failed"
                : ex.getBindingResult().getFieldErrors().stream().findFirst()
                        .map(error -> error.getField() + ": " + error.getDefaultMessage())
                        .orElse("Validation failed");
        log.warn("Rejected STOMP message: {}", message);
        return ApiResponse.error("VALIDATION_ERROR", message);
    }
}
