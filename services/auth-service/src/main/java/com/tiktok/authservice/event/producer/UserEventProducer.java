package com.tiktok.authservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.event.user.UserRegisteredEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserEventProducer {

    private static final String AGGREGATE_TYPE = "USER";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publishUserRegistered(UserRegisteredEvent event) {
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(String.valueOf(event.userId()))
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build();

        outboxEventRepository.save(outboxEvent);
    }
}
