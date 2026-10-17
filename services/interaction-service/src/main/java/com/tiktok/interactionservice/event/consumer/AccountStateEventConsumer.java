package com.tiktok.interactionservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.interactionservice.service.UserBanRegistry;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Takes a closed account's comments out of every listing, and puts them back when it reopens. A
 * deletion is a closure that never ends here.
 *
 * <p>ponytail: after a deletion the comments, likes and counters stay in storage, so like and
 * comment counts on other people's videos still include them. A physical purge with a recount is
 * separate work.
 *
 * <p>No claim on the eventId: one assignment guarded by occurredAt — see
 * {@link UserBanRegistry#recordClosure}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStateEventConsumer {

    private final UserBanRegistry userBanRegistry;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.account-events", groupId = "interaction-service")
    @SneakyThrows
    public void onMessage(String payload) {
        AccountStateChangedEvent event = objectMapper.readValue(payload, AccountStateChangedEvent.class);
        if (event.state() == null) {
            log.warn("AccountStateChangedEvent without a state, dropped: {}", payload);
            return;
        }
        userBanRegistry.recordClosure(event.userId(),
                event.state() == AccountState.ACTIVE ? null : event.occurredAt(), event.occurredAt());
    }
}
