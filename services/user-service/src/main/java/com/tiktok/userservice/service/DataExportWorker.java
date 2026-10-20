package com.tiktok.userservice.service;

import com.tiktok.userservice.config.DataExportProperties;
import com.tiktok.userservice.config.MinioProperties;
import com.tiktok.userservice.entity.DataExportRequest;
import com.tiktok.userservice.repository.DataExportRequestRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builds "Download your data" archives, one request per poll, oldest first.
 *
 * <p>A request is claimed (a lease, see V17) in a short transaction, built outside any — the build
 * calls three services and uploads a file, and no row lock should be held across that — and
 * finished with a conditional update. A failed build is left claimed and retried once the lease
 * runs out, until {@code maxAttempts}; a request canceled mid-build loses its file instead of
 * turning READY.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataExportWorker {

    private final DataExportRequestRepository repository;
    private final DataExportArchiver archiver;
    private final DataExportWriter writer;
    private final DataExportProperties properties;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${user.data-export.poll-millis}")
    public void buildNext() {
        DataExportRequest request = claimNext();
        if (request == null) {
            return;
        }
        try {
            build(request);
        } catch (Exception e) {
            if (request.getAttempts() >= properties.maxAttempts()) {
                transactionTemplate.executeWithoutResult(status -> repository.fail(request.getId(), Instant.now()));
                log.error("Data export {} for user {} failed for good after {} attempts",
                        request.getId(), request.getUserId(), request.getAttempts(), e);
            } else {
                log.warn("Data export {} for user {} failed (attempt {}); retrying after the lease",
                        request.getId(), request.getUserId(), request.getAttempts(), e);
            }
        }
    }

    private DataExportRequest claimNext() {
        Instant now = Instant.now();
        return transactionTemplate.execute(status -> repository
                .findNextClaimable(now.minus(properties.lease()))
                .flatMap(id -> {
                    repository.claim(id, now);
                    return repository.findById(id);
                })
                .orElse(null));
    }

    private void build(DataExportRequest request) throws Exception {
        List<DataExportSection> sections = archiver.collect(request.getUserId(), request.categoryList());

        Path file = Files.createTempFile("data-export-", ".zip");
        try {
            try (OutputStream out = Files.newOutputStream(file)) {
                writer.write(request.getFormat(), sections, out);
            }
            long size = Files.size(file);
            // Random, so the key cannot be guessed even where the bucket is publicly readable.
            String key = "exports/%d/%d-%s.zip".formatted(request.getUserId(), request.getId(), UUID.randomUUID());
            try (InputStream body = Files.newInputStream(file)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minioProperties.bucket())
                        .object(key)
                        .contentType("application/zip")
                        .stream(body, size, -1)
                        .build());
            }

            Instant readyAt = Instant.now();
            Integer completed = transactionTemplate.execute(status -> repository.complete(
                    request.getId(), key, size, readyAt, readyAt.plus(properties.availableFor())));
            if (completed == null || completed == 0) {
                // Canceled, or its account erased, while it was being built.
                removeQuietly(key);
                log.info("Data export {} was no longer wanted by the time it was built", request.getId());
                return;
            }
            log.info("Data export {} for user {} is ready ({} bytes)", request.getId(), request.getUserId(), size);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void removeQuietly(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(minioProperties.bucket()).object(key).build());
        } catch (Exception e) {
            log.warn("Could not remove the unwanted export {}", key, e);
        }
    }
}
