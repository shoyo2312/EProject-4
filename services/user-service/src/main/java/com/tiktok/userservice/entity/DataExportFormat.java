package com.tiktok.userservice.entity;

/** The file format of a "Download your data" archive, as the requester picked it. */
public enum DataExportFormat {
    /** One readable text file per kind of data. */
    TXT,
    /** One {@code user_data.json}, for importing elsewhere. */
    JSON
}
