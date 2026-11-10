package com.tiktok.chatservice.realtime;

import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.service.ChatEventPublisher;
import com.tiktok.event.user.UserBlockChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlockEventFanout {
    private final ObjectMapper objectMapper;
    private final ConversationRepository conversationRepository;
    private final ChatEventPublisher chatEventPublisher;

    // Inherits realtime-${random.uuid}, like NotificationFanout: every socket-owning instance
    // receives the event. Re-reading current views makes duplicates and old events harmless.
    @KafkaListener(topics = "user.block-events")
    public void onBlockChanged(String payload) {
        UserBlockChangedEvent event;
        try {
            event = objectMapper.readValue(payload, UserBlockChangedEvent.class);
            if (event == null || event.blockerId() == null || event.blockedId() == null) {
                log.warn("Unreadable block event dropped: missing pair");
                return;
            }
        } catch (Exception e) {
            log.warn("Unreadable block event dropped: {}", e.getMessage());
            return;
        }
        conversationRepository.findByParticipantKeyAndDeletedAtIsNull(
                        Conversation.buildParticipantKey(event.blockerId(), event.blockedId()))
                .ifPresent(chatEventPublisher::conversationChanged);
    }
}
