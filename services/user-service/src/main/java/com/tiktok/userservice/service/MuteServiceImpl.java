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
