package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class DataExportNotCancelableException extends ConflictException {

    public DataExportNotCancelableException() {
        super("DATA_EXPORT_NOT_CANCELABLE", "Only a request that is still processing can be canceled");
    }
}
