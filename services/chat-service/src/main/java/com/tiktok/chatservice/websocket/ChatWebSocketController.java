package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.request.TypingRequest;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.chatservice.service.TypingService;
import com.tiktok.common.exception.DomainException;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
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
    private final TypingService typingService;

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
     * "I am typing" / "I stopped". The user is the authenticated principal and the session is the one
     * the server assigned to this socket, never anything in the payload; a refusal is dropped without a
     * reply (see {@link TypingService}). Not a message: nothing is stored.
     */
    @MessageMapping("/chat.typing/{conversationId}")
    public void typing(@DestinationVariable String conversationId, @Payload TypingRequest request,
                       Principal principal, SimpMessageHeaderAccessor headers) {
        if (principal == null || request == null) {
            return;
        }
        typingService.update(conversationId, Long.valueOf(principal.getName()), headers.getSessionId(), request.typing());
    }

    /**
     * Without a handler the rejection is logged server-side and nothing reaches the client, which
     * sees its message silently vanish. This sends the reason back on the sender's own queue.
     */
    /**
     * A refusal from the service — blocked, not a participant, no such conversation — goes back to
     * the sender the way a REST caller would get it. Without this it only reached the broker's log,
     * and the message silently never appeared.
     */
    @MessageExceptionHandler(DomainException.class)
    @SendToUser("/queue/errors")
    public ApiResponse<Void> handleRefusal(DomainException ex) {
        log.debug("Refused STOMP message: {}", ex.getCode());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

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
