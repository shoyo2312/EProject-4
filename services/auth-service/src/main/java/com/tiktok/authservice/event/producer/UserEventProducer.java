package com.tiktok.authservice.event.producer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.event.DomainEvent;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.event.user.NewDeviceSignInEvent;
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
    static final String ACCOUNT_AGGREGATE = "ACCOUNT";
    static final String NEW_DEVICE_AGGREGATE = "NEW_DEVICE";

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

    /** Keyed by the account, so a close and the reopening after it arrive in order. */
    public void publishAccountStateChanged(AccountStateChangedEvent event) {
        save(ACCOUNT_AGGREGATE, event.userId(), event);
    }

    /**
     * Written in the transaction that remembers the device, so an alert cannot be lost after its
     * device was recorded as known — that device would never alert again.
     */
    public void publishNewDeviceSignIn(NewDeviceSignInEvent event) {
        save(NEW_DEVICE_AGGREGATE, event.userId(), event);
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
