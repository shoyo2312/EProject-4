package com.tiktok.userservice.service;

import com.tiktok.userservice.config.DataExportProperties;
import com.tiktok.userservice.config.MinioProperties;
import com.tiktok.userservice.dto.request.RequestDataExportRequest;
import com.tiktok.userservice.dto.response.DataExportAdminSummaryResponse;
import com.tiktok.userservice.dto.response.DataExportDownloadResponse;
import com.tiktok.userservice.dto.response.DataExportResponse;
import com.tiktok.userservice.entity.DataExportCategory;
import com.tiktok.userservice.entity.DataExportRequest;
import com.tiktok.userservice.entity.DataExportStatus;
import com.tiktok.userservice.exception.DataExportInProgressException;
import com.tiktok.userservice.exception.DataExportNotCancelableException;
import com.tiktok.userservice.exception.DataExportNotFoundException;
import com.tiktok.userservice.exception.DataExportUnavailableException;
import com.tiktok.userservice.repository.DataExportRequestRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DataExportServiceImpl implements DataExportService {

    /** The window the admin summary counts failures over. */
    private static final Duration FAILED_WINDOW = Duration.ofDays(7);

    private static final int RECENT_FAILURES = 20;

    private final DataExportRequestRepository repository;
    private final DataExportProperties properties;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    @Override
    @Transactional(readOnly = true)
    public List<DataExportResponse> list(Long userId) {
        Instant now = Instant.now();
        return repository.findTop20ByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(request -> DataExportResponse.of(request, now))
                .toList();
    }

    @Override
    @Transactional
    public DataExportResponse request(Long userId, RequestDataExportRequest request) {
        if (repository.hasProcessing(userId)) {
            throw new DataExportInProgressException();
        }
        repository.expireReadyOf(userId, Instant.now());

        // Declaration order, whatever order the client sent them in.
        List<String> categories = Arrays.stream(DataExportCategory.values())
                .filter(request.categories()::contains)
                .map(Enum::name)
                .toList();
        DataExportRequest saved;
        try {
            saved = repository.saveAndFlush(DataExportRequest.builder()
                    .userId(userId)
                    .format(request.format())
                    .categories(categories)
                    .status(DataExportStatus.PROCESSING)
                    .attempts(0)
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Two requests raced past the check above; the partial unique index let one through.
            throw new DataExportInProgressException();
        }
        return DataExportResponse.of(saved, Instant.now());
    }

    @Override
    @Transactional
    public DataExportResponse cancel(Long userId, Long requestId) {
        repository.findByIdAndUserIdAndDeletedAtIsNull(requestId, userId)
                .orElseThrow(DataExportNotFoundException::new);
        if (repository.cancel(requestId, userId, Instant.now()) == 0) {
            throw new DataExportNotCancelableException();
        }
        return repository.findById(requestId)
                .map(request -> DataExportResponse.of(request, Instant.now()))
                .orElseThrow(DataExportNotFoundException::new);
    }

    @Override
    @Transactional(readOnly = true)
    @SneakyThrows
    public DataExportDownloadResponse downloadLink(Long userId, Long requestId) {
        DataExportRequest request = repository.findByIdAndUserIdAndDeletedAtIsNull(requestId, userId)
                .orElseThrow(DataExportNotFoundException::new);
        Instant now = Instant.now();
        if (!request.isDownloadable(now)) {
            throw new DataExportUnavailableException();
        }

        String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(minioProperties.bucket())
                .object(request.getObjectKey())
                .expiry((int) properties.linkTtl().toSeconds())
                // Saved under a readable name rather than the random object key.
                .extraQueryParams(Map.of("response-content-disposition",
                        "attachment; filename=\"%s\"".formatted(fileName(request))))
                .build());
        return new DataExportDownloadResponse(url, now.plus(properties.linkTtl()));
    }

    @Override
    @Transactional(readOnly = true)
    public DataExportAdminSummaryResponse adminSummary() {
        Instant now = Instant.now();
        // Past this, every attempt's lease has run out and it is still not built.
        Instant stalledBefore = now.minus(properties.lease().multipliedBy(properties.maxAttempts()));
        Instant failedSince = now.minus(FAILED_WINDOW);

        Instant oldestProcessingSince = repository
                .findFirstByStatusAndDeletedAtIsNullOrderByCreatedAtAsc(DataExportStatus.PROCESSING)
                .map(DataExportRequest::getCreatedAt)
                .orElse(null);
        List<DataExportAdminSummaryResponse.Failure> recentFailures = repository
                .findByStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(DataExportStatus.FAILED,
                        PageRequest.of(0, RECENT_FAILURES))
                .stream()
                .map(DataExportAdminSummaryResponse.Failure::of)
                .toList();

        return new DataExportAdminSummaryResponse(
                repository.countByStatusAndDeletedAtIsNull(DataExportStatus.PROCESSING),
                oldestProcessingSince,
                repository.countByStatusAndDeletedAtIsNullAndCreatedAtBefore(DataExportStatus.PROCESSING,
                        stalledBefore),
                repository.countByStatusAndDeletedAtIsNullAndExpiresAtAfter(DataExportStatus.READY, now),
                failedSince,
                repository.countByStatusAndDeletedAtIsNullAndUpdatedAtAfter(DataExportStatus.FAILED, failedSince),
                recentFailures);
    }

    /** {@code nowa_data_json_2026-10-18.zip} */
    static String fileName(DataExportRequest request) {
        LocalDate day = LocalDate.ofInstant(request.getCreatedAt(), ZoneOffset.UTC);
        return "nowa_data_%s_%s.zip".formatted(request.getFormat().name().toLowerCase(Locale.ROOT), day);
    }
}
