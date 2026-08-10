package com.tiktok.userservice.service;

import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserMuteRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class BlockMuteServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private BlockService blockService;

    @Autowired
    private MuteService muteService;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserMuteRepository userMuteRepository;

    @BeforeEach
    void setUp() {
        userFollowRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userProfileRepository.deleteAll();

        userProfileService.createFromRegisteredEvent(1L, "alice");
        userProfileService.createFromRegisteredEvent(2L, "bob");
    }

    @Test
    @Transactional
    void block_unknownTarget_throwsNotFoundAndPersistsNothing() {
        assertThatThrownBy(() -> blockService.block(1L, 999L))
                .isInstanceOf(UserProfileNotFoundException.class);

        assertThat(userBlockRepository.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(1L, 999L)).isEmpty();
    }

    @Test
    @Transactional
    void mute_unknownTarget_throwsNotFoundAndPersistsNothing() {
        assertThatThrownBy(() -> muteService.mute(1L, 999L))
                .isInstanceOf(UserProfileNotFoundException.class);

        assertThat(userMuteRepository.findByMuterIdAndMutedIdAndDeletedAtIsNull(1L, 999L)).isEmpty();
    }

    @Test
    @Transactional
    void blockAndMute_existingTarget_stillSucceed() {
        assertThat(blockService.block(1L, 2L).blockedId()).isEqualTo(2L);
        assertThat(muteService.mute(2L, 1L).mutedId()).isEqualTo(1L);
    }
}
