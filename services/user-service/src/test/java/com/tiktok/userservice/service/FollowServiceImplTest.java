package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.AlreadyRequestedException;
import com.tiktok.userservice.exception.CannotFollowSelfException;
import com.tiktok.userservice.exception.FollowRequestNotFoundException;
import com.tiktok.userservice.exception.NotFollowingException;
import com.tiktok.userservice.exception.PrivateAccountException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.entity.OutboxEvent;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.as;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class FollowServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
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

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        followRequestRepository.deleteAll();
        userFollowRepository.deleteAll();
        userBlockRepository.deleteAll();
        userProfileRepository.deleteAll();
        // OutboxPublisher drains the table on a schedule and needs a non-null future to wait on,
        // same as every other outbox test in this codebase.
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
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
    void friendship_isTrueOnlyWhenBothFollowEachOther() {
        assertThat(followService.friendship(1L, 2L).friends()).isFalse();

        followService.follow(1L, 2L);
        assertThat(followService.friendship(1L, 2L).friends()).isFalse();

        followService.follow(2L, 1L);
        assertThat(followService.friendship(1L, 2L).friends()).isTrue();
        assertThat(followService.friendship(2L, 1L).friends()).isTrue();

        assertThat(followService.friendship(1L, 1L).friends()).isFalse();
    }

    @Test
    @Transactional
    void follow_self_throwsCannotFollowSelf() {
        assertThatThrownBy(() -> followService.follow(1L, 1L))
                .isInstanceOf(CannotFollowSelfException.class);
    }

    @Test
    @Transactional
    void follow_unknownTarget_throwsNotFoundAndLeavesCountersUntouched() {
        // No FK on user_follows.following_id and the counter update is an UPDATE ... WHERE that
        // matches nothing, so before the existence check this returned 200 and left the
        // follower's followingCount permanently overstating a non-existent edge.
        assertThatThrownBy(() -> followService.follow(1L, 999L))
                .isInstanceOf(UserProfileNotFoundException.class);

        assertThat(userProfileService.getByUserId(1L, 1L).followingCount()).isZero();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 999L)).isEmpty();
    }

    @Test
    @Transactional
    void follow_callerWithoutProfile_throwsNotFoundAndPersistsNothing() {
        // A caller whose UserRegisteredEvent never landed holds a valid token but no profile row.
        // incrementFollowingCount would match nothing, and the edge would leave an id with no
        // profile sitting in bob's followers list.
        assertThatThrownBy(() -> followService.follow(999L, 2L))
                .isInstanceOf(UserProfileNotFoundException.class);

        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isZero();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(999L, 2L)).isEmpty();
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
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
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
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        followService.follow(3L, 1L);
        blockService.block(1L, 2L);

        Page<UserProfileResponse> followers = followService.listFollowers(3L, 1L, PageRequest.of(0, 10));
        assertThat(followers.getContent()).extracting(UserProfileResponse::userId).containsExactly(3L);
    }

    @Test
    @Transactional
    void listFollowers_hidesAnEntryThatBlockedTheViewer_andCountsItOutOfTheTotal() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        followService.follow(2L, 1L);
        followService.follow(3L, 1L);
        // Carol blocks bob. Alice's follower list stays readable to bob — carol is not in it.
        blockService.block(3L, 2L);

        Page<UserProfileResponse> asBob = followService.listFollowers(2L, 1L, PageRequest.of(0, 10));

        assertThat(asBob.getContent()).extracting(UserProfileResponse::userId).containsExactly(2L);
        // The total counts what this viewer can reach, so a client paging towards it terminates.
        assertThat(asBob.getTotalElements()).isEqualTo(1);
    }

    /**
     * The follow edge and its announcement are one write. Sending to Kafka inline instead — which
     * is what this replaced — held a pooled connection for the broker round trip and failed the
     * whole follow when the broker was down, after the database had already accepted it.
     */
    @Test
    @Transactional
    void follow_writesTheEventToTheOutboxInsteadOfSendingItInline() {
        followService.follow(1L, 2L);

        assertThat(outboxEventRepository.findAll())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("UserFollowChangedEvent");
                    // Keyed by the followed user, so every change to one follower count keeps its
                    // order on one partition.
                    assertThat(event.getAggregateId()).isEqualTo("2");
                    assertThat(event.getPublishedAt()).isNull();
                    assertThat(event.getPayload()).contains("\"followerId\":1", "\"followed\":true");
                });
    }

    @Test
    @Transactional
    void unfollow_alsoGoesThroughTheOutbox() {
        followService.follow(1L, 2L);
        followService.unfollow(1L, 2L);

        assertThat(outboxEventRepository.findAll())
                .hasSize(2)
                .last()
                .extracting(OutboxEvent::getPayload, as(org.assertj.core.api.InstanceOfAssertFactories.STRING))
                .contains("\"followed\":false");
    }

    /** Straight on the column: the settings path is Task 4's, and going public there approves requests. */
    private void makePrivate(Long userId) {
        userProfileRepository.updatePrivateAccount(userId, true);
    }

    @Test
    @Transactional
    void follow_privateAccount_createsARequestNotAnEdge() {
        makePrivate(2L);

        FollowResponse response = followService.follow(1L, 2L);

        assertThat(response.status()).isEqualTo(FollowResponse.Status.REQUESTED);
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isZero();
        assertThat(outboxEventRepository.findAll())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo("FollowRequestedEvent");
                    // Keyed by the account asked, so one account's requests keep their order.
                    assertThat(event.getAggregateId()).isEqualTo("2");
                    assertThat(event.getPayload()).contains("\"requesterId\":1", "\"targetId\":2");
                });
    }

    @Test
    @Transactional
    void follow_privateAccountTwice_isAlreadyRequested() {
        makePrivate(2L);
        followService.follow(1L, 2L);

        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyRequestedException.class);
    }

    /** Going private keeps existing followers, so an edge already there still answers first. */
    @Test
    @Transactional
    void follow_privateAccountAlreadyFollowed_isAlreadyFollowing() {
        followService.follow(1L, 2L);
        makePrivate(2L);

        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyFollowingException.class);
    }

    /** The Follow button reads "Requested", and pressing it again withdraws — silently. */
    @Test
    @Transactional
    void unfollow_withAPendingRequest_withdrawsIt() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        outboxEventRepository.deleteAll();

        followService.unfollow(1L, 2L);

        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    @Transactional
    void approve_turnsTheRequestIntoAFollowAndSaysSo() {
        makePrivate(2L);
        followService.follow(1L, 2L);

        FollowResponse response = followService.approveFollowRequest(2L, 1L);

        assertThat(response.status()).isEqualTo(FollowResponse.Status.FOLLOWING);
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isPresent();
        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isEqualTo(1);
        assertThat(userProfileService.getByUserId(1L, 1L).followingCount()).isEqualTo(1);
        assertThat(outboxEventRepository.findAll())
                .filteredOn(event -> "UserFollowChangedEvent".equals(event.getEventType()))
                .singleElement()
                .extracting(OutboxEvent::getPayload, as(org.assertj.core.api.InstanceOfAssertFactories.STRING))
                .contains("\"followed\":true", "\"requestApproved\":true");
    }

    @Test
    @Transactional
    void approveAndReject_withNoPendingRequest_areNotFound() {
        assertThatThrownBy(() -> followService.approveFollowRequest(2L, 1L))
                .isInstanceOf(FollowRequestNotFoundException.class);
        assertThatThrownBy(() -> followService.rejectFollowRequest(2L, 1L))
                .isInstanceOf(FollowRequestNotFoundException.class);
    }

    /** A rejection is silent: no edge, no counter, nothing announced. */
    @Test
    @Transactional
    void reject_endsTheRequestWithoutAFollowOrAnAnnouncement() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        outboxEventRepository.deleteAll();

        followService.rejectFollowRequest(2L, 1L);

        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    /** The unique index covers live rows only, so a rejected requester can ask again. */
    @Test
    @Transactional
    void reject_thenAskingAgain_isANewRequest() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        followService.rejectFollowRequest(2L, 1L);

        assertThat(followService.follow(1L, 2L).status()).isEqualTo(FollowResponse.Status.REQUESTED);
        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isPresent();
    }

    @Test
    @Transactional
    void listFollowRequests_newestFirst() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        makePrivate(2L);
        followService.follow(1L, 2L);
        followService.follow(3L, 2L);

        assertThat(followService.listFollowRequests(2L, PageRequest.of(0, 10)).getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactly(3L, 1L);
    }

    /**
     * More than one request is the case that matters: each edge moves the counters through bulk
     * updates that clear the persistence context, and a request not yet ended by then is detached
     * and its end silently lost.
     */
    @Test
    @Transactional
    void approveAll_approvesEveryPendingRequest() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        makePrivate(2L);
        followService.follow(1L, 2L);
        followService.follow(3L, 2L);

        followService.approveAllFollowRequests(2L);

        assertThat(followRequestRepository.findByTargetIdAndDeletedAtIsNull(2L)).isEmpty();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isPresent();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(3L, 2L)).isPresent();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isEqualTo(2);
    }

    /**
     * A request left pending across a switch to public, then a direct follow: approving the
     * request later must end it without a second edge or a second count.
     */
    @Test
    @Transactional
    void approve_whenTheFollowAlreadyExists_onlyEndsTheRequest() {
        makePrivate(2L);
        followService.follow(1L, 2L);
        userProfileRepository.updatePrivateAccount(2L, false);
        followService.follow(1L, 2L);

        followService.approveFollowRequest(2L, 1L);

        assertThat(followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(userProfileService.getByUserId(2L, 2L).followerCount()).isEqualTo(1);
    }

    @Test
    @Transactional
    void friendship_saysFollowingRequestedAndPrivate() {
        makePrivate(2L);
        followService.follow(1L, 2L);

        FriendshipResponse requested = followService.friendship(1L, 2L);
        assertThat(requested.following()).isFalse();
        assertThat(requested.requested()).isTrue();
        assertThat(requested.privateAccount()).isTrue();

        followService.approveFollowRequest(2L, 1L);

        FriendshipResponse following = followService.friendship(1L, 2L);
        assertThat(following.following()).isTrue();
        assertThat(following.requested()).isFalse();
        assertThat(following.friends()).isFalse();
    }

    /** chat-service and interaction-service ask for anonymous viewers too; only the flag can be true. */
    @Test
    @Transactional
    void friendship_forAnAnonymousViewer_onlySaysWhetherTheAccountIsPrivate() {
        makePrivate(2L);

        FriendshipResponse anonymous = followService.friendship(null, 2L);

        assertThat(anonymous.privateAccount()).isTrue();
        assertThat(anonymous.following()).isFalse();
        assertThat(anonymous.requested()).isFalse();
        assertThat(anonymous.friends()).isFalse();
    }

    @Test
    @Transactional
    void followingAmong_answersTheSubsetFollowed() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        followService.follow(1L, 2L);

        assertThat(followService.followingAmong(1L, List.of(2L, 3L, 999L))).containsExactly(2L);
    }

    @Test
    void followingAmong_overTheBatchCap_isRejected() {
        List<Long> ids = LongStream.rangeClosed(1, UserProfileService.MAX_BATCH_IDS + 1).boxed().toList();

        assertThatThrownBy(() -> followService.followingAmong(1L, ids))
                .isInstanceOf(TooManyProfileIdsException.class);
    }

    /** A private account's lists are for its followers: 403 to a stranger; the owner and followers pass. */
    @Test
    @Transactional
    void lists_ofAPrivateAccount_refuseAStrangerButNotAFollowerOrTheOwner() {
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        followService.follow(1L, 2L);
        makePrivate(2L);

        assertThat(followService.listFollowers(1L, 2L, PageRequest.of(0, 10)).getContent())
                .extracting(UserProfileResponse::userId).containsExactly(1L);
        assertThat(followService.listFollowing(2L, 2L, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThatThrownBy(() -> followService.listFollowers(3L, 2L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
        assertThatThrownBy(() -> followService.listFollowing(3L, 2L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
    }
}
