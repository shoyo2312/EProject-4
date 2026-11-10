package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** The upload URL was issued but nothing was uploaded to it, or the upload has expired. */
public class UploadNotFoundException extends BadRequestException {

    public UploadNotFoundException() {
        super("UPLOAD_NOT_FOUND", "The uploaded file was not found — upload it again");
    }
}
