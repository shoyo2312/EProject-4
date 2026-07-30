package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Lets a connected client send a message over the socket itself, as an alternative to the
 * REST POST. Both paths funnel through MessageService, so persistence and broadcast happen
 * exactly the same way regardless of which one a client uses.
 */
@Controller
@RequiredArgsConstructor
public class ChatWebSocketController {

    private final MessageService messageService;

    @MessageMapping("/chat.send/{conversationId}")
    public void send(@DestinationVariable String conversationId, @Payload SendMessageRequest request, Principal principal) {
        Long senderId = Long.valueOf(principal.getName());
        messageService.sendMessage(conversationId, senderId, request);
    }
}
