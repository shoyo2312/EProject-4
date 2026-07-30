package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class UserProfileNotFoundException extends ResourceNotFoundException {

    public UserProfileNotFoundException(Long userId) {
        super("USER_PROFILE_NOT_FOUND", "No profile found for user id: " + userId);
    }
}
