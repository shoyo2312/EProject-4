package com.tiktok.searchservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.searchservice.index.OwnerBans;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Leaves a closed account's videos out of search, and puts them back when it reopens. A deletion is
 * a closure that never ends here; video-service's VideoDeletedEvents then take the documents out.
 *
 * <p>No claim on the eventId: it is one assignment, and OwnerBans ignores a change older than the
 * one it holds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStateEventConsumer {

    private final OwnerBans ownerBans;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.account-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload) {
        AccountStateChangedEvent event = objectMapper.readValue(payload, AccountStateChangedEvent.class);
        if (event.state() == null) {
            log.warn("AccountStateChangedEvent without a state, dropped: {}", payload);
            return;
        }
        ownerBans.recordClosure(event.userId(),
                event.state() == AccountState.ACTIVE ? null : event.occurredAt(), event.occurredAt());
    }
}
