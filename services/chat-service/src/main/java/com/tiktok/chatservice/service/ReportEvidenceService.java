package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.ReportEvidenceRequest;
import com.tiktok.chatservice.dto.response.ReportEvidenceResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.exception.InvalidMessageException;
import com.tiktok.chatservice.exception.ReportEvidenceUnavailableException;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reading a report's evidence is not sending: it needs membership and what the reporter can see,
 * and nothing of the send policy — a person who has been blocked must still be able to report.
 */
@Service
@RequiredArgsConstructor
public class ReportEvidenceService {

    static final int MAX_MESSAGES = 50;

    private final ConversationService conversationService;
    private final MessageRepository messageRepository;
    private final ChatUserPrefsRepository prefsRepository;

    public ReportEvidenceResponse capture(ReportEvidenceRequest request) {
        List<String> ids = request.messageIds();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_MESSAGES || new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidMessageException("Pick between 1 and " + MAX_MESSAGES + " distinct messages");
        }
        Conversation conversation = conversationService.requireParticipant(request.reporterId(), request.conversationId());
        if (conversation.getParticipantIds() == null || conversation.getParticipantIds().size() != 2) {
            throw new InvalidMessageException("Only a conversation between two people can be reported");
        }
        Long reported = conversation.getParticipantIds().stream()
                .filter(id -> !id.equals(request.reporterId())).findFirst()
                .orElseThrow(() -> new InvalidMessageException("Conversation has no other participant"));

        Instant cleared = prefsRepository.findById(request.reporterId())
                .map(prefs -> prefs.clearedAt(request.conversationId())).orElse(null);
        Map<String, Message> found = messageRepository.findInConversation(request.conversationId(), ids).stream()
                .collect(Collectors.toMap(Message::getId, Function.identity()));

        List<String> unavailable = ids.stream()
                .filter(id -> !reportable(found.get(id), request.reporterId(), cleared)).toList();
        if (!unavailable.isEmpty()) {
            throw new ReportEvidenceUnavailableException(unavailable);
        }

        List<ReportEvidenceResponse.Item> items = found.values().stream()
                .sorted(Comparator.comparing(Message::getSentAt).thenComparing(Message::getId))
                .map(m -> new ReportEvidenceResponse.Item(m.getId(), m.getSenderId(), m.getType().name(),
                        m.getSentAt(), m.getContent(), m.getStickerId(), m.getVideoId(), m.getStoryId()))
                .toList();
        return new ReportEvidenceResponse(request.conversationId(), reported, Instant.now(), items);
    }

    private static boolean reportable(Message m, Long reporterId, Instant cleared) {
        return m != null && !m.isDeleted() && !m.isRecalled() && !m.isHiddenFor(reporterId)
                && (cleared == null || m.getSentAt().isAfter(cleared));
    }
}
