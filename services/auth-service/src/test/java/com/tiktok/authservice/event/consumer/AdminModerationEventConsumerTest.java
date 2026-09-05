package com.tiktok.authservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.authservice.service.SessionRevoker;
import com.tiktok.authservice.service.UserModerationService;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminModerationEventConsumerTest {

    private static final long USER_ID = 42L;
    private static final long ADMIN_ID = 7L;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private UserRepository userRepository;
    private SessionRevoker sessionRevoker;
    private AdminModerationEventConsumer consumer;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sessionRevoker = mock(SessionRevoker.class);
        consumer = new AdminModerationEventConsumer(
                new UserModerationService(userRepository, sessionRevoker), objectMapper);

        user = User.builder()
                .username("victim")
                .email("victim@example.com")
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void banBlocksLoginAndKillsLiveSessions() throws Exception {
        consumer.onMessage(json(UserBannedEvent.of(USER_ID, ADMIN_ID, "spam")), header("UserBannedEvent"));

        assertThat(saved().getStatus()).isEqualTo(UserStatus.BANNED);
        verify(sessionRevoker).revokeAllSessions(USER_ID);
    }

    @Test
    void unbanRestoresTheAccount() throws Exception {
        user.ban();

        consumer.onMessage(json(UserUnbannedEvent.of(USER_ID, ADMIN_ID, "appeal upheld")),
                header("UserUnbannedEvent"));

        assertThat(saved().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /** The two user events are byte-identical as JSON, so a wrong header would ban on an unban. */
    @Test
    void routingIsHeaderOnly() throws Exception {
        String payload = json(UserBannedEvent.of(USER_ID, ADMIN_ID, "spam"));

        consumer.onMessage(payload, header("VideoTakenDownEvent"));
        consumer.onMessage(payload, null);

        verify(userRepository, never()).save(any());
        verify(sessionRevoker, never()).revokeAllSessions(anyLong());
    }

    @Test
    void unknownUserIsANoOp() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        consumer.onMessage(json(UserBannedEvent.of(USER_ID, ADMIN_ID, "spam")), header("UserBannedEvent"));

        verify(userRepository, never()).save(any());
        verify(sessionRevoker, never()).revokeAllSessions(anyLong());
    }

    private User saved() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    private String json(Object event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }
}
