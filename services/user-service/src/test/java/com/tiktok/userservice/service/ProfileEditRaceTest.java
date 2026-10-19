package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * A profile edit reads the row, changes it, and writes it at commit. The follower and following
 * counters move in between by bulk updates that leave the version alone, so the edit's version
 * check passes — and an UPDATE that names every column writes the count it read straight back.
 * Nothing recounts afterwards, so the profile shows the wrong number for good.
 *
 * <p>Each test holds the edit's transaction open around a follow that commits on another thread,
 * which is the exact order a lost race leaves behind, without racing real requests. Against an
 * entity that is not {@code @DynamicUpdate} both cases end with a count of 0.
 *
 * <p>Not @Transactional: the follow has to commit on its own, before the edit does.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ProfileEditRaceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        // The follow-changed realtime publish (confirmed send) needs a non-null future to .get() on.
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        userFollowRepository.deleteAll();
        followRequestRepository.deleteAll();
        userProfileRepository.deleteAll();

        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
    }

    @Test
    void editingTheProfile_whileSomeoneFollows_keepsTheFollowerCount() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            userProfileService.updateOwnProfile(2L, new UpdateProfileRequest(null, "new bio", null));
            followOnAnotherThread(1L, 2L);
        });

        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(2L).orElseThrow().getFollowerCount())
                .isEqualTo(1);
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(2L).orElseThrow().getBio())
                .isEqualTo("new bio");
    }

    @Test
    void replacingTheAvatar_whileFollowingSomeone_keepsTheFollowingCount() {
        String avatarUrl = "https://cdn.tiktok-clone.local/avatars/1.png";
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            userProfileService.replaceOwnAvatarUrl(1L, avatarUrl);
            followOnAnotherThread(1L, 2L);
        });

        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow().getFollowingCount())
                .isEqualTo(1);
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow().getAvatarUrl())
                .isEqualTo(avatarUrl);
    }

    /** Its own thread, so its own transaction: it commits now, not with the caller's. */
    private void followOnAnotherThread(Long followerId, Long followingId) {
        CompletableFuture.runAsync(() -> followService.follow(followerId, followingId)).join();
    }
}
