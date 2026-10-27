package com.tiktok.chatservice.dto.response;

/**
 * One frame on a user's own {@code /user/queue/chat}. It reaches a participant that does not know
 * the conversation id yet, so it cannot be a topic.
 *
 * <ul>
 *   <li>{@code MESSAGE}: a message that just landed in one of the user's conversations, rendered for
 *       that user.</li>
 *   <li>{@code CONVERSATION}: the conversation as that user sees it after something changed — a
 *       request arrived, was accepted or deleted, a preview was recalled or hidden.</li>
 *   <li>{@code MESSAGE_UPDATED}: reactions or a recall on an existing message; no text in it.</li>
 *   <li>{@code CONVERSATION_REMOVED}: the user deleted a whole conversation from their own side. Sent
 *       to that user only, so their other tabs drop it; the conversation id is in
 *       {@code conversationId}.</li>
 *   <li>{@code MESSAGE_HIDDEN}: the user deleted a message from their own side. Sent to that user
 *       only — never to the other participant, and never on the shared topic.</li>
 * </ul>
 *
 * The fields that do not apply to a type are null.
 */
public record ChatEvent(
        Type type,
        String conversationId,
        MessageResponse message,
        ConversationResponse conversation,
        MessageUpdateResponse update,
        String messageId
) {

    public enum Type {
        MESSAGE,
        CONVERSATION,
        MESSAGE_UPDATED,
        MESSAGE_HIDDEN,
        CONVERSATION_REMOVED
    }

    public static ChatEvent message(MessageResponse message) {
        return new ChatEvent(Type.MESSAGE, message.conversationId(), message, null, null, null);
    }

    public static ChatEvent conversation(ConversationResponse conversation) {
        return new ChatEvent(Type.CONVERSATION, conversation.id(), null, conversation, null, null);
    }

    public static ChatEvent updated(MessageUpdateResponse update) {
        return new ChatEvent(Type.MESSAGE_UPDATED, update.conversationId(), null, null, update, null);
    }

    public static ChatEvent removed(String conversationId) {
        return new ChatEvent(Type.CONVERSATION_REMOVED, conversationId, null, null, null, null);
    }

    public static ChatEvent hidden(String conversationId, String messageId) {
        return new ChatEvent(Type.MESSAGE_HIDDEN, conversationId, null, null, null, messageId);
    }
}
