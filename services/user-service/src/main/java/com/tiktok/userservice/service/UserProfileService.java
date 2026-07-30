package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;

public interface UserProfileService {

    UserProfileResponse getByUserId(Long userId);

    UserProfileResponse updateOwnProfile(Long userId, UpdateProfileRequest request);

    void createFromRegisteredEvent(Long userId, String username, String email);
}
