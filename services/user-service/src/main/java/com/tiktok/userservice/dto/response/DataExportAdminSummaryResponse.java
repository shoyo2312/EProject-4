package com.tiktok.userservice.dto.response;

import com.tiktok.userservice.entity.DataExportCategory;
import com.tiktok.userservice.entity.DataExportFormat;
import com.tiktok.userservice.entity.DataExportRequest;

import java.time.Instant;
import java.util.List;

/**
 * "Download your data" as the admin console watches it. A request that keeps failing is otherwise
 * only a log line in user-service, and its owner sees nothing but "Failed".
 *
 * @param processing             requests waiting for or being built right now
 * @param oldestProcessingSince  when the longest-waiting of them was made; null when none is
 * @param stalled                of {@code processing}, those older than every attempt's lease put
 *                               together — no worker is getting through them
 * @param readyFiles             finished archives still downloadable, so still taking space
 * @param failedSince            the start of the window {@code failed} counts
 * @param failed                 requests given up on since {@code failedSince}
 * @param recentFailures         the latest of those, newest first
 */
public record DataExportAdminSummaryResponse(
        long processing,
        Instant oldestProcessingSince,
        long stalled,
        long readyFiles,
        Instant failedSince,
        long failed,
        List<Failure> recentFailures
) {

    /** @param failedAt when it was given up on */
    public record Failure(
            Long id,
            Long userId,
            DataExportFormat format,
            List<DataExportCategory> categories,
            int attempts,
            Instant requestedAt,
            Instant failedAt
    ) {

        public static Failure of(DataExportRequest request) {
            return new Failure(request.getId(), request.getUserId(), request.getFormat(),
                    request.categoryList(), request.getAttempts(), request.getCreatedAt(),
                    request.getUpdatedAt());
        }
    }
}
