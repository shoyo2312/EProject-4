package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class DataExportUnavailableException extends ConflictException {

    public DataExportUnavailableException() {
        super("DATA_EXPORT_UNAVAILABLE", "This file is not available to download");
    }
}
