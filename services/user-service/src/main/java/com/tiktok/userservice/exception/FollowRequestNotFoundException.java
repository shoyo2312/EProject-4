package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class FollowRequestNotFoundException extends ResourceNotFoundException {

    public FollowRequestNotFoundException() {
        super("FOLLOW_REQUEST_NOT_FOUND", "No pending follow request from this user");
    }
}
