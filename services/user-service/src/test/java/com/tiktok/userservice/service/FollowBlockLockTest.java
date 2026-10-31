package com.tiktok.userservice.service;

import com.tiktok.userservice.entity.FollowRequest;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.exception.CannotFollowBlockedUserException;
import com.tiktok.userservice.repository.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowBlockLockTest {
    private final UserFollowRepository follows = mock(UserFollowRepository.class);
    private final UserProfileRepository profiles = mock(UserProfileRepository.class);
    private final UserBlockRepository blocks = mock(UserBlockRepository.class);
    private final FollowRequestRepository requests = mock(FollowRequestRepository.class);
    private final UserPairLock lock = mock(UserPairLock.class);
    private final FollowServiceImpl service = new FollowServiceImpl(follows, profiles, blocks, requests,
            mock(UserProfileBatchAssembler.class), mock(ProfileVisibilityGuard.class),
            mock(UserEventPublisher.class), lock);

    @Test
    void follow_locksBeforeAnyReadThenChecksTheBlock() {
        when(profiles.findByUserIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(UserProfile.builder().build()));
        when(profiles.existsByUserIdAndDeletedAtIsNull(1L)).thenReturn(true);
        when(blocks.existsBlockBetween(1L, 2L)).thenReturn(true);
        assertThatThrownBy(() -> service.follow(1L, 2L)).isInstanceOf(CannotFollowBlockedUserException.class);
        var order = inOrder(lock, profiles, blocks);
        order.verify(lock).acquire(1L, 2L);
        order.verify(profiles).findByUserIdAndDeletedAtIsNull(2L);
        order.verify(profiles).existsByUserIdAndDeletedAtIsNull(1L);
        order.verify(blocks).existsBlockBetween(1L, 2L);
        verifyNoInteractions(follows, requests);
    }

    @Test
    void approval_locksBeforeLoadingTheRequestAndRefusesABlock() {
        when(requests.findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Optional.of(FollowRequest.builder().requesterId(1L).targetId(2L).build()));
        when(blocks.existsBlockBetween(2L, 1L)).thenReturn(true);
        assertThatThrownBy(() -> service.approveFollowRequest(2L, 1L))
                .isInstanceOf(CannotFollowBlockedUserException.class);
        var order = inOrder(lock, requests, blocks);
        order.verify(lock).acquire(2L, 1L);
        order.verify(requests).findByRequesterIdAndTargetIdAndDeletedAtIsNull(1L, 2L);
        order.verify(blocks).existsBlockBetween(2L, 1L);
        verifyNoInteractions(follows);
    }

    @Test
    void bulkApproval_locksInOrderAndRereadsRequestsAfterWaiting() {
        var one = FollowRequest.builder().requesterId(1L).targetId(9L).build();
        var two = FollowRequest.builder().requesterId(2L).targetId(9L).build();
        var late = FollowRequest.builder().requesterId(3L).targetId(9L).build();
        when(requests.findByTargetIdAndDeletedAtIsNull(9L)).thenReturn(List.of(two, one), List.of(two, late));
        when(blocks.existsBlockBetween(2L, 9L)).thenReturn(true);
        service.approveAllFollowRequests(9L);
        var order = inOrder(requests, lock, blocks);
        order.verify(requests).findByTargetIdAndDeletedAtIsNull(9L);
        order.verify(lock).acquire(9L, 1L);
        order.verify(lock).acquire(9L, 2L);
        order.verify(requests).findByTargetIdAndDeletedAtIsNull(9L);
        order.verify(blocks).existsBlockBetween(2L, 9L);
        assertThat(two.isDeleted()).isTrue();
        assertThat(late.isDeleted()).isFalse();
        verifyNoInteractions(follows);
    }

    @Test
    void unfollow_takesTheSameLockBeforeRemovingAnEdge() {
        when(follows.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L))
                .thenReturn(Optional.of(UserFollow.builder().build()));
        service.unfollow(1L, 2L);
        var order = inOrder(lock, follows, profiles);
        order.verify(lock).acquire(1L, 2L);
        order.verify(follows).findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L);
        order.verify(profiles).decrementFollowingCount(1L);
    }
}
