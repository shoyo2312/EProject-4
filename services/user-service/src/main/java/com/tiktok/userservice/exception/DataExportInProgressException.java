package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class DataExportInProgressException extends ConflictException {

    public DataExportInProgressException() {
        super("DATA_EXPORT_IN_PROGRESS", "A data request is already being processed");
    }
}
