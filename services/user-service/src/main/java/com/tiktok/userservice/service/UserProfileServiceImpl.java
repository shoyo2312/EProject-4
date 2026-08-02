package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.mapper.UserProfileMapper;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;
    private final ProfileVisibilityGuard profileVisibilityGuard;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResponse getByUserId(Long viewerId, Long userId) {
        profileVisibilityGuard.requireVisible(viewerId, userId);

        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));
        return userProfileMapper.toResponse(profile);
    }

    @Override
    @Transactional
    public UserProfileResponse updateOwnProfile(Long userId, UpdateProfileRequest request) {
        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        profile.updateProfile(request.displayName(), request.bio(), request.avatarUrl());

        return userProfileMapper.toResponse(profile);
    }

    @Override
    @Transactional
    public void createFromRegisteredEvent(Long userId, String username, String email) {
        if (userProfileRepository.existsByUserIdAndDeletedAtIsNull(userId)) {
            return;
        }

        UserProfile profile = UserProfile.builder()
                .userId(userId)
                .displayName(username)
                .build();

        userProfileRepository.save(profile);
    }
}
