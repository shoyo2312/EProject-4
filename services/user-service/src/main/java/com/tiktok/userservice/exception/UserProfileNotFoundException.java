package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class UserProfileNotFoundException extends ResourceNotFoundException {

    public UserProfileNotFoundException(Long userId) {
        super("USER_PROFILE_NOT_FOUND", "No profile found for user id: " + userId);
    }

    /** Same code as the id lookup: a client addressing a profile either way gets one answer. */
    public UserProfileNotFoundException(String username) {
        super("USER_PROFILE_NOT_FOUND", "No profile found for username: " + username);
    }
}
