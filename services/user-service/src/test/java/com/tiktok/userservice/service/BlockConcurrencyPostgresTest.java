package com.tiktok.userservice.service;

import com.tiktok.userservice.exception.AlreadyBlockedException;
import com.tiktok.userservice.exception.CannotFollowBlockedUserException;
import com.tiktok.userservice.exception.FollowRequestNotFoundException;
import com.tiktok.userservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual SQL, transaction rollback and independent connections; requires Docker/PostgreSQL. */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class BlockConcurrencyPostgresTest {
    @Container @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private final BlockService blocks;
    private final FollowService follows;
    private final UserProfileService profiles;
    private final UserBlockRepository blockRows;
    private final UserFollowRepository followRows;
    private final FollowRequestRepository requests;
    private final UserProfileRepository profileRows;
    private final OutboxEventRepository outbox;
    private final TransactionTemplate transaction;

    @Autowired
    BlockConcurrencyPostgresTest(BlockService blocks, FollowService follows, UserProfileService profiles,
                                 UserBlockRepository blockRows, UserFollowRepository followRows,
                                 FollowRequestRepository requests, UserProfileRepository profileRows,
                                 OutboxEventRepository outbox, PlatformTransactionManager manager) {
        this.blocks = blocks;
        this.follows = follows;
        this.profiles = profiles;
        this.blockRows = blockRows;
        this.followRows = followRows;
        this.requests = requests;
        this.profileRows = profileRows;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(manager);
    }

    @BeforeEach
    void setUp() {
        requests.deleteAll();
        followRows.deleteAll();
        blockRows.deleteAll();
        profileRows.deleteAll();
        outbox.deleteAll();
        profiles.createFromRegisteredEvent(1L, "alice", null);
        profiles.createFromRegisteredEvent(2L, "bob", null);
    }

    @Test
    void timestampsAdvanceAcrossBothDirectionsAndAllDeletedHistory() {
        assertThat(blocks.states(1L, List.of(2L)).getFirst().version()).isZero();
        blocks.block(1L, 2L);
        var first = blocks.states(1L, List.of(2L)).getFirst();
        assertThat(first.blockedByViewer()).isTrue();
        blocks.unblock(1L, 2L);
        var second = blocks.states(1L, List.of(2L)).getFirst();
        assertThat(second.blockedByViewer()).isFalse();
        assertThat(second.version()).isGreaterThan(first.version());
        blocks.block(2L, 1L);
        var third = blocks.states(1L, List.of(2L)).getFirst();
        assertThat(third.blockedByViewer()).isFalse();
        assertThat(third.blockedViewer()).isTrue();
        assertThat(third.version()).isGreaterThan(second.version());
        blocks.unblock(2L, 1L);
        assertThat(blocks.states(1L, List.of(2L)).getFirst().version()).isGreaterThan(third.version());
        blocks.block(1L, 2L);
        assertThat(blockRows.findAll()).hasSize(3);
        assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateType().equals("USER_BLOCK")).hasSize(5);
    }

    @Test
    void rollbackRemovesTheBlockAndItsOutboxAnnouncementTogether() {
        transaction.executeWithoutResult(status -> {
            blocks.block(1L, 2L);
            assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateType().equals("USER_BLOCK")).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(blockRows.findAll()).isEmpty();
        assertThat(outbox.findAll()).isEmpty();
    }

    @Test
    void oppositeBlocks_removeEachFollowAndCounterOnce() throws Exception {
        follows.follow(1L, 2L);
        follows.follow(2L, 1L);
        race(() -> blocks.block(1L, 2L), () -> blocks.block(2L, 1L));
        assertNoEdgesOrCounts();
        assertThat(blockRows.findAll()).hasSize(2);
        assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateType().equals("USER_FOLLOW"))
                .filteredOn(e -> e.getPayload().contains("\"followed\":false")).hasSize(2);
    }

    @Test
    void duplicateBlocks_onlyOneAnnouncementAndCounterRemoval() throws Exception {
        follows.follow(1L, 2L);
        Runnable duplicate = () -> {
            try { blocks.block(1L, 2L); } catch (AlreadyBlockedException expected) { /* losing duplicate */ }
        };
        race(duplicate, duplicate);
        assertNoEdgesOrCounts();
        assertThat(blockRows.findAll()).hasSize(1);
        assertThat(outbox.findAll()).filteredOn(e -> e.getAggregateType().equals("USER_BLOCK")).hasSize(1);
    }

    @Test
    void racingFollowCannotLeaveAnEdgeAcrossABlock() throws Exception {
        race(() -> {
            try { follows.follow(1L, 2L); } catch (CannotFollowBlockedUserException expected) { /* block won */ }
        }, () -> blocks.block(2L, 1L));
        assertNoEdgesOrCounts();
    }

    @Test
    void racingApprovalCannotLeaveAnEdgeAcrossABlock() throws Exception {
        transaction.executeWithoutResult(status -> profileRows.updatePrivateAccount(2L, true));
        follows.follow(1L, 2L);
        race(() -> {
            try { follows.approveFollowRequest(2L, 1L); }
            catch (FollowRequestNotFoundException | CannotFollowBlockedUserException expected) { /* block won */ }
        }, () -> blocks.block(2L, 1L));
        assertNoEdgesOrCounts();
        assertThat(requests.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
    }

    private void assertNoEdgesOrCounts() {
        assertThat(followRows.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L)).isEmpty();
        assertThat(followRows.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(2L, 1L)).isEmpty();
        assertThat(profileRows.findAll()).allSatisfy(profile -> {
            assertThat(profile.getFollowerCount()).isZero();
            assertThat(profile.getFollowingCount()).isZero();
        });
    }

    private void race(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> a = executor.submit(() -> { await(start); first.run(); });
            Future<?> b = executor.submit(() -> { await(start); second.run(); });
            start.countDown();
            a.get(20, TimeUnit.SECONDS);
            b.get(20, TimeUnit.SECONDS);
        }
    }

    private void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
