package com.tiktok.userservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.service.AccountStateService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Hides or shows a profile as its owner closes or reopens the account, and unwinds it when the
 * deletion runs.
 *
 * <p>Closing and reopening take no inbox claim: each is an assignment guarded by
 * {@code occurredAt}, so a redelivery writes the same values and a late one changes nothing.
 * Deletion moves counters once per edge, so it is claimed in the same transaction as the work.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStateEventConsumer {

    private final InboxEventRepository inboxEventRepository;
    private final AccountStateService accountStateService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.account-events", groupId = "user-service")
    @Transactional
    @SneakyThrows
    public void onMessage(String payload) {
        AccountStateChangedEvent event = objectMapper.readValue(payload, AccountStateChangedEvent.class);
        if (event.state() == null) {
            log.warn("AccountStateChangedEvent without a state, dropped: {}", payload);
            return;
        }

        switch (event.state()) {
            case ACTIVE -> accountStateService.applyDeactivated(event.userId(), false, event.occurredAt());
            case DEACTIVATED -> accountStateService.applyDeactivated(event.userId(), true, event.occurredAt());
            case DELETED -> {
                int claimed = inboxEventRepository.tryClaim(SnowflakeIdGenerator.nextId(), event.eventId(),
                        event.getClass().getSimpleName(), Instant.now());
                if (claimed == 0) {
                    log.info("Skipping already-processed account deletion eventId={}", event.eventId());
                    return;
                }
                accountStateService.erase(event.userId(), event.occurredAt());
            }
        }
    }
}
