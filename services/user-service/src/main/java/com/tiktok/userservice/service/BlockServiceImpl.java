package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.BlockResponse;
import com.tiktok.userservice.dto.response.BlockStateResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.FollowRequest;
import com.tiktok.userservice.entity.UserBlock;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.exception.AlreadyBlockedException;
import com.tiktok.userservice.exception.CannotBlockSelfException;
import com.tiktok.userservice.exception.NotBlockedException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BlockServiceImpl implements BlockService {

    private final UserBlockRepository userBlockRepository;
    private final UserFollowRepository userFollowRepository;
    private final FollowRequestRepository followRequestRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileBatchAssembler profileBatchAssembler;
    private final UserEventPublisher userEventPublisher;
    private final UserPairLock pairLock;

    @Override
    @Transactional
    public BlockResponse block(Long blockerId, Long blockedId) {
        pairLock.acquire(blockerId, blockedId);
        if (blockerId.equals(blockedId)) {
            throw new CannotBlockSelfException();
        }

        if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(blockedId)) {
            throw new UserProfileNotFoundException(blockedId);
        }

        if (userBlockRepository.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(blockerId, blockedId).isPresent()) {
            throw new AlreadyBlockedException();
        }

        // The pair lock serializes current writers. Keep the unique-index backstop for older
        // instances during deployment; flushing translates its violation into the same 409.
        Instant changedAt = nextChangeAt(blockerId, blockedId);
        try {
            UserBlock block = userBlockRepository.saveAndFlush(UserBlock.builder()
                    .blockerId(blockerId)
                    .blockedId(blockedId)
                    .build());
            userBlockRepository.advanceCreatedAt(block.getId(), changedAt);
        } catch (DataIntegrityViolationException ex) {
            throw new AlreadyBlockedException();
        }

        // Blocking severs any existing follow relationship in either direction, so neither
        // side keeps showing up in the other's followers/following list after the block.
        removeFollowIfPresent(blockerId, blockedId);
        removeFollowIfPresent(blockedId, blockerId);
        // Pending requests go with the edges: a blocked account must not sit in the requests list,
        // and approving one would create a follow across the block.
        removeFollowRequestIfPresent(blockerId, blockedId);
        removeFollowRequestIfPresent(blockedId, blockerId);

        userEventPublisher.publishBlockChanged(blockerId, blockedId, true);

        return new BlockResponse(blockerId, blockedId);
    }

    @Override
    @Transactional
    public void unblock(Long blockerId, Long blockedId) {
        pairLock.acquire(blockerId, blockedId);
        UserBlock block = userBlockRepository.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(blockerId, blockedId)
                .orElseThrow(NotBlockedException::new);

        userBlockRepository.endBlock(block.getId(), nextChangeAt(blockerId, blockedId));
        userEventPublisher.publishBlockChanged(blockerId, blockedId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listBlocked(Long userId, Pageable pageable) {
        Page<Long> blockedIds = userBlockRepository.findByBlockerIdAndDeletedAtIsNull(userId, pageable)
                .map(UserBlock::getBlockedId);
        return profileBatchAssembler.toResponses(blockedIds);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isBlockedBetween(Long userA, Long userB) {
        return userBlockRepository.existsBlockBetween(userA, userB);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BlockStateResponse> states(Long viewerId, List<Long> otherIds) {
        if (otherIds.size() > 100) {
            throw new TooManyProfileIdsException(100);
        }
        List<Long> ids = otherIds.stream().distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, UserBlockRepository.BlockState> found = userBlockRepository.findStates(viewerId, ids).stream()
                .collect(Collectors.toMap(UserBlockRepository.BlockState::getUserId, Function.identity()));
        return ids.stream().map(id -> {
            var state = found.get(id);
            return state == null ? new BlockStateResponse(id, false, false, 0)
                    : new BlockStateResponse(id, state.getBlockedByViewer(), state.getBlockedViewer(), state.getVersion());
        }).toList();
    }

    private Instant nextChangeAt(Long a, Long b) {
        long previous = userBlockRepository.findStates(a, List.of(b)).stream()
                .mapToLong(UserBlockRepository.BlockState::getVersion).max().orElse(0);
        return Instant.ofEpochMilli(Math.max(System.currentTimeMillis(), previous + 1));
    }

    private void removeFollowIfPresent(Long followerId, Long followingId) {
        userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId)
                .ifPresent(follow -> {
                    follow.markDeleted();
                    userProfileRepository.decrementFollowingCount(followerId);
                    userProfileRepository.decrementFollowerCount(followingId);
                    // Same announcement unfollow makes: a follow ended, whichever path ended it.
                    userEventPublisher.publishFollowChanged(followerId, followingId, false);
                });
    }

    private void removeFollowRequestIfPresent(Long requesterId, Long targetId) {
        followRequestRepository.findByRequesterIdAndTargetIdAndDeletedAtIsNull(requesterId, targetId)
                .ifPresent(FollowRequest::markDeleted);
    }
}
