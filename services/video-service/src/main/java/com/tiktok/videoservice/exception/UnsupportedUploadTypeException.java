package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class UnsupportedUploadTypeException extends BadRequestException {

    public UnsupportedUploadTypeException(String contentType, Object supported) {
        super("UNSUPPORTED_UPLOAD_TYPE",
                "Cannot upload %s — supported types are %s".formatted(contentType, supported));
    }
}
