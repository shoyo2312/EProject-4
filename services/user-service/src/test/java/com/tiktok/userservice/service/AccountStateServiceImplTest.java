package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.OutboxEventRepository;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountStateServiceImplTest {

    private static final Long ALICE = 1L;
    private static final Long BOB = 2L;
    private static final Long CAROL = 3L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AccountStateService accountStateService;

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
    private UserBlockRepository userBlockRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        followRequestRepository.deleteAll();
        userFollowRepository.deleteAll();
        userBlockRepository.deleteAll();
        userProfileRepository.deleteAll();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        userProfileService.createFromRegisteredEvent(ALICE, "alice", null);
        userProfileService.createFromRegisteredEvent(BOB, "bob", null);
        userProfileService.createFromRegisteredEvent(CAROL, "carol", null);
    }

    @Test
    @Transactional
    void aClosedAccount_isNotThereForAnyoneElse_butKeepsItsEdgesAndCounts() {
        followService.follow(BOB, ALICE);
        followService.follow(ALICE, CAROL);

        accountStateService.applyDeactivated(ALICE, true, Instant.now());

        assertThatThrownBy(() -> userProfileService.getByUserId(BOB, ALICE))
                .isInstanceOf(UserProfileNotFoundException.class);
        assertThatThrownBy(() -> userProfileService.getByUsername(null, "alice"))
                .isInstanceOf(UserProfileNotFoundException.class);
        assertThatThrownBy(() -> followService.listFollowers(BOB, ALICE, PageRequest.of(0, 10)))
                .isInstanceOf(UserProfileNotFoundException.class);
        assertThatThrownBy(() -> followService.follow(CAROL, ALICE))
                .isInstanceOf(UserProfileNotFoundException.class);
        assertThat(userProfileService.getByUserIds(BOB, List.of(ALICE, CAROL)))
                .extracting(UserProfileResponse::userId).containsExactly(CAROL);
        assertThat(userProfileService.search(BOB, "alice", PageRequest.of(0, 10))).isEmpty();
        assertThat(userProfileService.searchIds("alice", 10)).isEmpty();
        // Left out of other people's lists, with a total that agrees.
        assertThat(followService.listFollowing(BOB, BOB, PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(followService.listFollowers(CAROL, CAROL, PageRequest.of(0, 10)).getTotalElements()).isZero();

        assertThat(followService.friendship(BOB, ALICE).following()).isTrue();
        assertThat(userProfileService.getByUserId(BOB, BOB).followingCount()).isEqualTo(1);
        assertThat(userProfileService.getByUserId(CAROL, CAROL).followerCount()).isEqualTo(1);
    }

    @Test
    @Transactional
    void reopening_bringsTheAccountBack() {
        Instant closedAt = Instant.now();
        accountStateService.applyDeactivated(ALICE, true, closedAt);
        accountStateService.applyDeactivated(ALICE, false, closedAt.plusSeconds(1));

        assertThat(userProfileService.getByUserId(BOB, ALICE).userId()).isEqualTo(ALICE);
    }

    @Test
    @Transactional
    void anOlderChangeArrivingLate_changesNothing() {
        Instant reopenedAt = Instant.now();
        accountStateService.applyDeactivated(ALICE, false, reopenedAt);
        accountStateService.applyDeactivated(ALICE, true, reopenedAt.minusSeconds(5));

        assertThat(userProfileService.getByUserId(BOB, ALICE).userId()).isEqualTo(ALICE);
    }

    @Test
    @Transactional
    void erase_endsEveryEdgeAndCorrectsTheOtherSidesCounts() {
        followService.follow(BOB, ALICE);
        followService.follow(ALICE, CAROL);
        userProfileRepository.updatePrivateAccount(ALICE, true);
        followService.follow(CAROL, ALICE);
        outboxEventRepository.deleteAll();

        accountStateService.erase(ALICE, Instant.now());

        assertThat(userProfileService.getByUserId(BOB, BOB).followingCount()).isZero();
        assertThat(userProfileService.getByUserId(CAROL, CAROL).followerCount()).isZero();
        assertThat(followService.friendship(BOB, ALICE).following()).isFalse();
        assertThat(followService.friendship(CAROL, ALICE).requested()).isFalse();
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(ALICE)).isEmpty();
        // One unfollow announced per edge, so follow caches and stats frames hear of it.
        assertThat(outboxEventRepository.findAll()).extracting(OutboxEvent::getEventType)
                .containsExactly("UserFollowChangedEvent", "UserFollowChangedEvent");
    }
}
