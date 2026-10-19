package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class DataExportNotFoundException extends ResourceNotFoundException {

    public DataExportNotFoundException() {
        super("DATA_EXPORT_NOT_FOUND", "No such data request");
    }
}
