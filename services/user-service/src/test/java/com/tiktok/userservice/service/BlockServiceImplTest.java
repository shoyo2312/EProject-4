package com.tiktok.userservice.service;

import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.entity.UserBlock;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.exception.AlreadyBlockedException;
import com.tiktok.userservice.exception.NotBlockedException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlockServiceImplTest {
    private final UserBlockRepository blocks = mock(UserBlockRepository.class);
    private final UserFollowRepository follows = mock(UserFollowRepository.class);
    private final FollowRequestRepository requests = mock(FollowRequestRepository.class);
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final UserPairLock lock = mock(UserPairLock.class);
    private final TestTransactions transactions = new TestTransactions();
    private BlockService service;

    @BeforeEach
    void setUp() {
        var target = new BlockServiceImpl(blocks, follows, requests, profiles,
                mock(UserProfileBatchAssembler.class),
                new UserEventPublisher(outbox, JsonMapper.builder().findAndAddModules().build()), lock);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (BlockService) proxy.getProxy();
        when(profiles.existsByUserIdAndDeletedAtIsNull(anyLong())).thenReturn(true);
        when(blocks.saveAndFlush(any())).thenAnswer(invocation -> UserBlock.builder()
                .id(10L).blockerId(1L).blockedId(2L).build());
    }

    @Test
    void blockAndUnblock_writeExactlyOnceInTheCallingTransaction() {
        when(outbox.save(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(transactions.commits).isZero();
            return invocation.getArgument(0);
        });
        service.block(1L, 2L);
        assertThat(transactions.commits).isEqualTo(1);
        ArgumentCaptor<OutboxEvent> blocked = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(blocked.capture());
        assertThat(blocked.getValue().getPayload()).contains("\"blocked\":true");
        when(blocks.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Optional.of(UserBlock.builder().id(10L).build()));
        reset(outbox);
        when(outbox.save(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(transactions.commits).isEqualTo(1);
            return invocation.getArgument(0);
        });
        service.unblock(1L, 2L);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getPayload()).contains("\"blocked\":false");
        assertThat(event.getValue().getAggregateId()).isEqualTo("1:2");
        assertThat(transactions.commits).isEqualTo(2);
        verify(lock, times(2)).acquire(1L, 2L);
    }

    @Test
    void block_removesEachEdgeOnceAndRetainsFollowAnnouncements() {
        when(follows.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Optional.of(UserFollow.builder().build()));
        when(follows.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(2L, 1L))
                .thenReturn(Optional.of(UserFollow.builder().build()));
        service.block(1L, 2L);
        verify(profiles).decrementFollowingCount(1L);
        verify(profiles).decrementFollowingCount(2L);
        verify(profiles).decrementFollowerCount(1L);
        verify(profiles).decrementFollowerCount(2L);
        ArgumentCaptor<OutboxEvent> rows = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox, times(3)).save(rows.capture());
        assertThat(rows.getAllValues()).filteredOn(e -> e.getAggregateType().equals("USER_BLOCK"))
                .singleElement().satisfies(e -> assertThat(e.getPayload()).contains("\"blocked\":true"));
        assertThat(rows.getAllValues()).filteredOn(e -> e.getAggregateType().equals("USER_FOLLOW")).hasSize(2);
        var order = inOrder(lock, blocks, follows);
        order.verify(lock).acquire(1L, 2L);
        order.verify(blocks).findByBlockerIdAndBlockedIdAndDeletedAtIsNull(1L, 2L);
        order.verify(blocks).findStates(1L, List.of(2L));
        order.verify(blocks).saveAndFlush(any());
        order.verify(blocks).advanceCreatedAt(eq(10L), any());
        order.verify(follows).findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L);
    }

    @Test
    void duplicateBlockAndMissingUnblock_emitNothingAndRollBack() {
        when(blocks.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Optional.of(UserBlock.builder().build()));
        assertThatThrownBy(() -> service.block(1L, 2L)).isInstanceOf(AlreadyBlockedException.class);
        assertThatThrownBy(() -> service.unblock(2L, 1L)).isInstanceOf(NotBlockedException.class);
        verifyNoInteractions(outbox, follows, requests);
        verify(profiles, never()).decrementFollowerCount(any());
        assertThat(transactions.rollbacks).isEqualTo(2);
    }

    @Test
    void uniqueConstraintBackstop_emitsNothingAndNeverMovesCounters() {
        when(blocks.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> service.block(1L, 2L)).isInstanceOf(AlreadyBlockedException.class);
        verifyNoInteractions(outbox, follows, requests);
        verify(profiles, never()).decrementFollowingCount(any());
        verify(profiles, never()).decrementFollowerCount(any());
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void pairTimestamp_advancesEvenWhenPreviousTimeIsInTheFuture() {
        var previous = mock(UserBlockRepository.BlockState.class);
        long future = System.currentTimeMillis() + 60_000;
        when(previous.getVersion()).thenReturn(future);
        when(blocks.findStates(1L, List.of(2L))).thenReturn(List.of(previous));
        service.block(1L, 2L);
        verify(blocks).advanceCreatedAt(10L, Instant.ofEpochMilli(future + 1));
        when(blocks.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Optional.of(UserBlock.builder().id(10L).build()));
        service.unblock(1L, 2L);
        verify(blocks).endBlock(10L, Instant.ofEpochMilli(future + 1));
    }

    @Test
    void states_useOneQueryAndDefaultMissingPairsToZero() {
        var found = mock(UserBlockRepository.BlockState.class);
        when(found.getUserId()).thenReturn(2L);
        when(found.getBlockedViewer()).thenReturn(true);
        when(found.getVersion()).thenReturn(123L);
        when(blocks.findStates(1L, List.of(2L, 3L))).thenReturn(List.of(found));
        var result = service.states(1L, List.of(2L, 3L, 2L));
        assertThat(result).containsExactly(
                new com.tiktok.userservice.dto.response.BlockStateResponse(2L, false, true, 123),
                new com.tiktok.userservice.dto.response.BlockStateResponse(3L, false, false, 0));
        verify(blocks).findStates(1L, List.of(2L, 3L));
        verifyNoMoreInteractions(blocks);
        assertThatThrownBy(() -> service.states(1L, LongStream.range(0, 101).boxed().toList()))
                .isInstanceOf(TooManyProfileIdsException.class);
    }

    // Exercises Spring's transaction interceptor without a database. SQL visibility, rollback of
    // actual rows, and competing connections belong to the PostgreSQL integration tests.
    private static class TestTransactions extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
