package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.StoryClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.request.StoryReplyRequest;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.StoryCard;
import com.tiktok.chatservice.exception.StoryNotAvailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Answering a story from its viewer. The story's owner is read from story-service for the sender,
 * the thread with that owner is the existing get-or-create (so block, DM privacy and message-request
 * rules apply as for any first message), and the reply is one ordinary send: the same idempotency,
 * request policy and delivery as every other message.
 */
@Service
@RequiredArgsConstructor
public class StoryReplyService {

    private final StoryClient storyClient;
    private final ConversationService conversationService;
    private final MessageService messageService;

    public MessageResponse reply(Long senderId, StoryReplyRequest request) {
        StoryCard card = storyClient.viewable(senderId, List.of(request.storyId())).get(request.storyId());
        if (card == null || !"AVAILABLE".equals(card.state()) || card.ownerId() == null) {
            throw new StoryNotAvailableException();
        }
        ConversationResponse conversation = conversationService.getOrCreate(senderId, card.ownerId());
        return messageService.sendMessage(conversation.id(), senderId,
                SendMessageRequest.storyReply(request.storyId(), request.content(), request.clientRequestId()));
    }
}
