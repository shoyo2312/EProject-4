package com.tiktok.userservice.dto.response;

import com.tiktok.userservice.entity.DataExportCategory;
import com.tiktok.userservice.entity.DataExportFormat;
import com.tiktok.userservice.entity.DataExportRequest;
import com.tiktok.userservice.entity.DataExportStatus;

import java.time.Instant;
import java.util.List;

/**
 * One request as its owner sees it. {@code status} is the stored one except for a READY file past
 * {@code expiresAt}, which reads EXPIRED before the sweep has caught up with it.
 *
 * @param readyAt   when the file was finished; null until READY
 * @param expiresAt the last moment it can be downloaded; null until READY
 */
public record DataExportResponse(
        Long id,
        DataExportFormat format,
        List<DataExportCategory> categories,
        DataExportStatus status,
        Instant requestedAt,
        Instant readyAt,
        Instant expiresAt,
        Long sizeBytes
) {

    public static DataExportResponse of(DataExportRequest request, Instant now) {
        DataExportStatus status = request.getStatus() == DataExportStatus.READY && !request.isDownloadable(now)
                ? DataExportStatus.EXPIRED
                : request.getStatus();
        return new DataExportResponse(request.getId(), request.getFormat(), request.categoryList(), status,
                request.getCreatedAt(), request.getReadyAt(), request.getExpiresAt(), request.getSizeBytes());
    }
}
