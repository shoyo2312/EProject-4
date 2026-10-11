package com.tiktok.authservice.event.producer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.event.DomainEvent;
import com.tiktok.event.user.SocialAvatarDiscoveredEvent;
import com.tiktok.event.user.UserRegisteredEvent;
import com.tiktok.event.user.UsernameChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserEventProducer {

    /** Each aggregate type has its own topic — see {@link OutboxPublisher}. */
    static final String USER_AGGREGATE = "USER";
    static final String USERNAME_AGGREGATE = "USERNAME";
    static final String SOCIAL_AVATAR_AGGREGATE = "SOCIAL_AVATAR";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishUserRegistered(UserRegisteredEvent event) {
        save(USER_AGGREGATE, event.userId(), event);
    }

    public void publishUsernameChanged(UsernameChangedEvent event) {
        save(USERNAME_AGGREGATE, event.userId(), event);
    }

    /**
     * A new account's provider picture, queued behind its registration. Every other sign-in sends
     * the same event straight to the broker through {@link SocialAvatarEventProducer}.
     */
    public void publishSocialAvatarDiscovered(SocialAvatarDiscoveredEvent event) {
        save(SOCIAL_AVATAR_AGGREGATE, event.userId(), event);
    }

    @SneakyThrows
    private void save(String aggregateType, Long userId, DomainEvent event) {
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(String.valueOf(userId))
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build();

        outboxEventRepository.save(outboxEvent);
    }
}
