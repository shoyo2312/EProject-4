package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.response.ConversationExportResponse;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.ReplyQuote;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.service.MessageService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Service-to-service: a user's chat history for user-service's "Download your data" job, which
 * runs with no signed-in user to forward. api-gateway denies this prefix; {@code SecurityConfig}
 * checks the internal token, like user-service's {@code /users/internal/**}.
 */
@RestController
@RequestMapping("/api/v1/conversations/internal")
@RequiredArgsConstructor
@Validated
public class InternalChatExportController {

    private final ConversationRepository conversationRepository;
    private final MessageService messageService;

    @GetMapping("/users/{userId}")
    public ApiResponse<List<ConversationExportResponse>> conversationsOf(@PathVariable Long userId) {
        return ApiResponse.success(conversationRepository
                .findByParticipantIdsAndDeletedAtIsNullOrderByLastMessageAtDesc(userId).stream()
                .map(c -> new ConversationExportResponse(c.getId(), c.getParticipantIds(), c.getCreatedAt()))
                .toList());
    }

    /** {@code viewerId} is the person asking for their data: the history comes back as they see it. */
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<ExportPage> messages(
            @PathVariable String conversationId,
            @RequestParam Long viewerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
        MessagePageResponse page = messageService.exportMessages(conversationId, viewerId, cursor, size);
        return ApiResponse.success(new ExportPage(page.items().stream().map(InternalChatExportController::exportItem).toList(),
                page.nextCursor(), page.hasMore()));
    }

    /** Keep TEXT/STICKER shapes; video exports contain identifiers and captions, never card media. */
    private static Object exportItem(MessageResponse message) {
        if (message.type() == MessageType.VIDEO) {
            return new VideoExportMessage(message.id(), message.conversationId(), message.senderId(), message.type(),
                    message.recalledAt() == null && message.video() != null ? message.video().videoId() : null,
                    message.content(), message.sentAt(), message.recalledAt());
        }
        if (message.replyTo() != null && message.replyTo().video() != null) {
            var quote = message.replyTo();
            return message.withReplyTo(new ReplyQuote(quote.messageId(), quote.state(),
                    quote.senderId(), quote.type(), quote.content(), quote.sticker()));
        }
        return message;
    }

    public record ExportPage(List<Object> items, String nextCursor, boolean hasMore) {}
    public record VideoExportMessage(String id, String conversationId, Long senderId,
                                    MessageType type, String videoId,
                                    String content, Instant sentAt, Instant recalledAt) {}
}
