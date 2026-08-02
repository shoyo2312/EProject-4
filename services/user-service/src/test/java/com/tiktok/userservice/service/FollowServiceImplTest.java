package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.CannotFollowSelfException;
import com.tiktok.userservice.exception.NotFollowingException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
class FollowServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private BlockService blockService;

    @BeforeEach
    void setUp() {
        userFollowRepository.deleteAll();
        userBlockRepository.deleteAll();
        userProfileRepository.deleteAll();

        userProfileService.createFromRegisteredEvent(1L, "alice", "alice@example.com");
        userProfileService.createFromRegisteredEvent(2L, "bob", "bob@example.com");
    }

    @Test
    @Transactional
    void follow_thenCountsAndListsReflectIt() {
        followService.follow(1L, 2L);

        UserProfileResponse aliceProfile = userProfileService.getByUserId(1L, 1L);
        UserProfileResponse bobProfile = userProfileService.getByUserId(2L, 2L);

        assertThat(aliceProfile.followingCount()).isEqualTo(1);
        assertThat(bobProfile.followerCount()).isEqualTo(1);

        Page<UserProfileResponse> bobFollowers = followService.listFollowers(2L, 2L, PageRequest.of(0, 10));
        assertThat(bobFollowers.getContent()).extracting(UserProfileResponse::userId).containsExactly(1L);

        Page<UserProfileResponse> aliceFollowing = followService.listFollowing(1L, 1L, PageRequest.of(0, 10));
        assertThat(aliceFollowing.getContent()).extracting(UserProfileResponse::userId).containsExactly(2L);
    }

    @Test
    @Transactional
    void follow_self_throwsCannotFollowSelf() {
        assertThatThrownBy(() -> followService.follow(1L, 1L))
                .isInstanceOf(CannotFollowSelfException.class);
    }

    @Test
    @Transactional
    void follow_twice_throwsAlreadyFollowing() {
        followService.follow(1L, 2L);

        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyFollowingException.class);
    }

    @Test
    @Transactional
    void unfollow_whenNotFollowing_throwsNotFollowing() {
        assertThatThrownBy(() -> followService.unfollow(1L, 2L))
                .isInstanceOf(NotFollowingException.class);
    }

    @Test
    @Transactional
    void unfollow_thenCountsDropAndCanFollowAgain() {
        followService.follow(1L, 2L);
        followService.unfollow(1L, 2L);

        UserProfileResponse bobProfile = userProfileService.getByUserId(2L, 2L);
        assertThat(bobProfile.followerCount()).isZero();

        followService.follow(1L, 2L);
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isEqualTo(1);
    }

    @Test
    @Transactional
    void listFollowers_viewerBlockedByOwner_throwsNotFound() {
        userProfileService.createFromRegisteredEvent(3L, "carol", "carol@example.com");
        followService.follow(3L, 1L);
        blockService.block(1L, 2L);

        // Without the guard, bob could still enumerate alice's whole follower graph after
        // being blocked, even though block() severed their own follow edges.
        assertThatThrownBy(() -> followService.listFollowers(2L, 1L, PageRequest.of(0, 10)))
                .isInstanceOf(UserProfileNotFoundException.class);
        assertThatThrownBy(() -> followService.listFollowing(2L, 1L, PageRequest.of(0, 10)))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    @Test
    @Transactional
    void listFollowers_unrelatedViewer_stillSeesList() {
        userProfileService.createFromRegisteredEvent(3L, "carol", "carol@example.com");
        followService.follow(3L, 1L);
        blockService.block(1L, 2L);

        Page<UserProfileResponse> followers = followService.listFollowers(3L, 1L, PageRequest.of(0, 10));
        assertThat(followers.getContent()).extracting(UserProfileResponse::userId).containsExactly(3L);
    }
}
