package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;

public interface UserProfileService {

    UserProfileResponse getByUserId(Long viewerId, Long userId);

    UserProfileResponse updateOwnProfile(Long userId, UpdateProfileRequest request);

    /**
     * Creates the profile a newly registered account gets by default.
     *
     * <p>Takes no email on purpose: nothing in a profile is addressed by email, and a copy kept
     * here would be a second place for it to be wrong once the account changes it. auth-service
     * owns that field.
     */
    void createFromRegisteredEvent(Long userId, String username);
}
