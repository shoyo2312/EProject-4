package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class ModerationTermNotFoundException extends ResourceNotFoundException {

    public ModerationTermNotFoundException(Long termId) {
        super("MODERATION_TERM_NOT_FOUND", "Moderation term not found: " + termId);
    }
}
