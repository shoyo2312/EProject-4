package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserFollow;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A relationship row can outlive the profile it points at — the profile is soft-deleted, or its
 * UserRegisteredEvent never landed. The assembler used to throw UserProfileNotFoundException for
 * such an id, which failed the whole page: one ghost follower took down the followers list for
 * everyone who followed that account, permanently and with no API-level repair.
 *
 * <p>The rows are inserted through the repository rather than through follow()/block(), because
 * follow() now rejects an unknown user on both sides; the state under test is one that arises
 * after the fact, not one the API can still create.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserProfileBatchAssemblerTest {

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
    private UserMuteRepository userMuteRepository;

    @BeforeEach
    void setUp() {
        userFollowRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userProfileRepository.deleteAll();

        userProfileService.createFromRegisteredEvent(1L, "alice", "alice@example.com");
        userProfileService.createFromRegisteredEvent(2L, "bob", "bob@example.com");
    }

    @Test
    @Transactional
    void listFollowers_withOneGhostFollower_stillReturnsTheRealOnes() {
        followService.follow(1L, 2L);
        givenFollowEdge(999L, 2L);

        Page<UserProfileResponse> followers = followService.listFollowers(2L, 2L, PageRequest.of(0, 10));

        assertThat(followers.getContent())
                .as("the ghost id is dropped, the real follower survives")
                .extracting(UserProfileResponse::userId)
                .containsExactly(1L);
    }

    @Test
    @Transactional
    void listFollowers_withOnlyGhostFollowers_returnsEmptyPageNotAnError() {
        givenFollowEdge(998L, 2L);
        givenFollowEdge(999L, 2L);

        Page<UserProfileResponse> followers = followService.listFollowers(2L, 2L, PageRequest.of(0, 10));

        assertThat(followers.getContent()).isEmpty();
        assertThat(followers.getTotalElements())
                .as("the edges still exist, so the total keeps counting them and paging stays consistent")
                .isEqualTo(2);
    }

    @Test
    @Transactional
    void listFollowers_pagingStillReportsTheEdgeCountNotTheProfileCount() {
        followService.follow(1L, 2L);
        givenFollowEdge(999L, 2L);

        // Sorted so the assertion is about the drop, not about unspecified row order.
        Page<UserProfileResponse> firstPage =
                followService.listFollowers(2L, 2L, PageRequest.of(0, 1, Sort.by("id").ascending()));

        assertThat(firstPage.getContent()).extracting(UserProfileResponse::userId).containsExactly(1L);
        assertThat(firstPage.getTotalElements()).isEqualTo(2);
    }

    @Test
    @Transactional
    void listFollowers_withNoFollowers_returnsEmptyPage() {
        Page<UserProfileResponse> followers = followService.listFollowers(2L, 2L, PageRequest.of(0, 10));

        assertThat(followers.getContent()).isEmpty();
        assertThat(followers.getTotalElements()).isZero();
    }

    /**
     * Inserts the follow row directly: this is the leftover of a user whose profile is gone.
     */
    private void givenFollowEdge(Long followerId, Long followingId) {
        userFollowRepository.save(UserFollow.builder()
                .followerId(followerId)
                .followingId(followingId)
                .build());
    }
}
