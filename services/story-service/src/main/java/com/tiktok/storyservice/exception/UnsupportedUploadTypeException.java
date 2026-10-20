package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class UnsupportedUploadTypeException extends BadRequestException {

    public UnsupportedUploadTypeException(String type, Object supported) {
        super("UNSUPPORTED_UPLOAD_TYPE",
                "Cannot post %s as a story — supported types are %s".formatted(type, supported));
    }
}
