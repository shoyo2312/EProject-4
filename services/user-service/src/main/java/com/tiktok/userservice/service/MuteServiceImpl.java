package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.MuteResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserMute;
import com.tiktok.userservice.exception.AlreadyMutedException;
import com.tiktok.userservice.exception.CannotMuteSelfException;
import com.tiktok.userservice.exception.NotMutedException;
import com.tiktok.userservice.repository.UserMuteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deliberately has no block check, unlike FollowServiceImpl.follow. Follow is a mutually visible
 * relationship, so a block must reject it; mute is a one-sided, silent flag that only filters the
 * muter's own feed, so muting someone who blocked you is redundant rather than wrong. Rejecting it
 * would also leak the block back to the caller — exactly what ProfileVisibilityGuard hides — and
 * would stop a user from downgrading a block to a mute after unblocking.
 */
@Service
@RequiredArgsConstructor
public class MuteServiceImpl implements MuteService {

    private final UserMuteRepository userMuteRepository;
    private final UserProfileBatchAssembler profileBatchAssembler;

    @Override
    @Transactional
    public MuteResponse mute(Long muterId, Long mutedId) {
        if (muterId.equals(mutedId)) {
            throw new CannotMuteSelfException();
        }

        if (userMuteRepository.findByMuterIdAndMutedIdAndDeletedAtIsNull(muterId, mutedId).isPresent()) {
            throw new AlreadyMutedException();
        }

        userMuteRepository.save(UserMute.builder()
                .muterId(muterId)
                .mutedId(mutedId)
                .build());

        return new MuteResponse(muterId, mutedId);
    }

    @Override
    @Transactional
    public void unmute(Long muterId, Long mutedId) {
        UserMute mute = userMuteRepository.findByMuterIdAndMutedIdAndDeletedAtIsNull(muterId, mutedId)
                .orElseThrow(NotMutedException::new);

        mute.markDeleted();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listMuted(Long userId, Pageable pageable) {
        Page<Long> mutedIds = userMuteRepository.findByMuterIdAndDeletedAtIsNull(userId, pageable)
                .map(UserMute::getMutedId);
        return profileBatchAssembler.toResponses(mutedIds);
    }
}
