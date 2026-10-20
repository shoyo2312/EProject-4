package com.tiktok.userservice.service;

import com.tiktok.userservice.config.DataExportProperties;
import com.tiktok.userservice.config.MinioProperties;
import com.tiktok.userservice.entity.DataExportRequest;
import com.tiktok.userservice.repository.DataExportRequestRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Marks ready files EXPIRED once their four days are up, and removes every file nobody can
 * download any more — expired, replaced, or belonging to an erased account. The row stays (soft
 * delete only); its object key is cleared once the file is gone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataExportCleanup {

    private final DataExportRequestRepository repository;
    private final DataExportProperties properties;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(fixedDelayString = "${user.data-export.cleanup-millis}")
    public void sweep() {
        Integer expired = transactionTemplate.execute(status -> repository.expireDue(Instant.now()));
        if (expired != null && expired > 0) {
            log.info("{} data export(s) expired", expired);
        }

        for (DataExportRequest request : repository.findFilesToRemove(PageRequest.of(0, properties.cleanupBatchSize()))) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(minioProperties.bucket())
                        .object(request.getObjectKey())
                        .build());
                transactionTemplate.executeWithoutResult(
                        status -> repository.clearObjectKey(request.getId(), request.getObjectKey()));
            } catch (Exception e) {
                // Left for the next sweep: the key stays on the row until the file is really gone.
                log.warn("Could not remove data export file {}", request.getObjectKey(), e);
            }
        }
    }
}
