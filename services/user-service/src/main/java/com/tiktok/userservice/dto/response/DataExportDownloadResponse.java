package com.tiktok.userservice.dto.response;

import java.time.Instant;

/** A signed link to the archive, good until {@code expiresAt} — a few minutes, not the file's four days. */
public record DataExportDownloadResponse(
        String url,
        Instant expiresAt
) {
}
