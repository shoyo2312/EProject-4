package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.BlockResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserBlock;
import com.tiktok.userservice.exception.AlreadyBlockedException;
import com.tiktok.userservice.exception.CannotBlockSelfException;
import com.tiktok.userservice.exception.NotBlockedException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BlockServiceImpl implements BlockService {

    private final UserBlockRepository userBlockRepository;
    private final UserFollowRepository userFollowRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileBatchAssembler profileBatchAssembler;

    @Override
    @Transactional
    public BlockResponse block(Long blockerId, Long blockedId) {
        if (blockerId.equals(blockedId)) {
            throw new CannotBlockSelfException();
        }

        if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(blockedId)) {
            throw new UserProfileNotFoundException(blockedId);
        }

        if (userBlockRepository.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(blockerId, blockedId).isPresent()) {
            throw new AlreadyBlockedException();
        }

        userBlockRepository.save(UserBlock.builder()
                .blockerId(blockerId)
                .blockedId(blockedId)
                .build());

        // Blocking severs any existing follow relationship in either direction, so neither
        // side keeps showing up in the other's followers/following list after the block.
        removeFollowIfPresent(blockerId, blockedId);
        removeFollowIfPresent(blockedId, blockerId);

        return new BlockResponse(blockerId, blockedId);
    }

    @Override
    @Transactional
    public void unblock(Long blockerId, Long blockedId) {
        UserBlock block = userBlockRepository.findByBlockerIdAndBlockedIdAndDeletedAtIsNull(blockerId, blockedId)
                .orElseThrow(NotBlockedException::new);

        block.markDeleted();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listBlocked(Long userId, Pageable pageable) {
        Page<Long> blockedIds = userBlockRepository.findByBlockerIdAndDeletedAtIsNull(userId, pageable)
                .map(UserBlock::getBlockedId);
        return profileBatchAssembler.toResponses(blockedIds);
    }

    private void removeFollowIfPresent(Long followerId, Long followingId) {
        userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId)
                .ifPresent(follow -> {
                    follow.markDeleted();
                    userProfileRepository.decrementFollowingCount(followerId);
                    userProfileRepository.decrementFollowerCount(followingId);
                });
    }
}
