package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessageRequestSummaryResponse;
import com.tiktok.chatservice.entity.Conversation;

import java.util.List;

public interface ConversationService {

    ConversationResponse getOrCreate(Long currentUserId, Long otherUserId);

    /** The viewer's inbox: ordinary conversations plus requests they started. Not requests waiting on them. */
    List<ConversationResponse> listForUser(Long currentUserId);

    ConversationResponse getById(Long currentUserId, String conversationId);

    /** Pending requests waiting on the viewer, newest first. */
    List<ConversationResponse> listRequests(Long currentUserId);

    MessageRequestSummaryResponse requestSummary(Long currentUserId);

    /** Recipient only. Idempotent: accepting an accepted request returns it unchanged. */
    ConversationResponse acceptRequest(Long currentUserId, String conversationId);

    /** Recipient only. Idempotent: deleting a deleted request returns it unchanged. */
    ConversationResponse declineRequest(Long currentUserId, String conversationId);

    /**
     * A pending request whose two sides now follow each other becomes an ordinary conversation.
     * Answers the conversation as it stands; a failed friendship lookup leaves it pending, which is
     * the stricter of the two outcomes.
     */
    Conversation promoteIfMutual(Conversation conversation);

    /**
     * The person who deleted a request is sending into it: the thread becomes ordinary so the other
     * side can reply. Applies the other account's DM audience first, as opening a new thread would.
     * Idempotent; answers the conversation as it stands afterwards.
     */
    Conversation reopenForSend(Conversation conversation, Long senderId);

    /** Loads the raw entity and asserts {@code currentUserId} is a participant, for use by MessageService. */
    Conversation requireParticipant(Long currentUserId, String conversationId);
}
