package com.tiktok.userservice.service;

import com.tiktok.userservice.client.DataExportSources;
import com.tiktok.userservice.dto.request.RequestDataExportRequest;
import com.tiktok.userservice.dto.response.DataExportAdminSummaryResponse;
import com.tiktok.userservice.dto.response.DataExportResponse;
import com.tiktok.userservice.entity.DataExportCategory;
import com.tiktok.userservice.entity.DataExportFormat;
import com.tiktok.userservice.entity.DataExportRequest;
import com.tiktok.userservice.entity.DataExportStatus;
import com.tiktok.userservice.exception.DataExportInProgressException;
import com.tiktok.userservice.exception.DataExportNotCancelableException;
import com.tiktok.userservice.exception.DataExportNotFoundException;
import com.tiktok.userservice.exception.DataExportUnavailableException;
import com.tiktok.userservice.repository.DataExportRequestRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The request → build → download → expire lifecycle against a real Postgres, where the partial
 * unique index, the SKIP LOCKED claim and every conditional update actually run. Other services
 * and object storage are mocked.
 */
@SpringBootTest(properties = {
        // The schedulers fire once at startup on an empty table; after that the test drives them.
        "user.data-export.poll-millis=3600000",
        "user.data-export.cleanup-millis=3600000"
})
@Testcontainers
@ActiveProfiles("test")
class DataExportLifecycleTest {

    private static final Long ALICE = 1L;
    private static final Long BOB = 2L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private MinioClient minioClient;

    @MockitoBean
    private DataExportSources sources;

    @Autowired
    private DataExportService service;

    @Autowired
    private DataExportWorker worker;

    @Autowired
    private DataExportCleanup cleanup;

    @Autowired
    private AccountStateService accountStateService;

    @Autowired
    private DataExportRequestRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() throws Exception {
        repository.deleteAll();
        reset(minioClient, sources);
        when(sources.videosOf(any())).thenReturn(List.of());
        when(sources.activityOf(any(), anyString())).thenReturn(List.of());
        when(sources.conversationsOf(any())).thenReturn(List.of());
        when(minioClient.getPresignedObjectUrl(any())).thenReturn("https://minio.test/signed");
    }

    @Test
    void aRequestIsBuiltIntoADownloadableFile() throws Exception {
        DataExportResponse requested = request(ALICE);
        assertThat(requested.status()).isEqualTo(DataExportStatus.PROCESSING);
        assertThat(requested.categories()).containsExactly(DataExportCategory.LIKES_AND_FAVORITES,
                DataExportCategory.PROFILE_AND_SETTINGS);
        assertThatThrownBy(() -> service.downloadLink(ALICE, requested.id()))
                .isInstanceOf(DataExportUnavailableException.class);

        worker.buildNext();

        ArgumentCaptor<PutObjectArgs> upload = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(upload.capture());
        assertThat(upload.getValue().object()).startsWith("exports/1/" + requested.id() + "-").endsWith(".zip");

        DataExportResponse ready = service.list(ALICE).getFirst();
        assertThat(ready.status()).isEqualTo(DataExportStatus.READY);
        assertThat(ready.expiresAt()).isEqualTo(ready.readyAt().plus(Duration.ofDays(4)));
        assertThat(ready.sizeBytes()).isPositive();
        // A bulk update skips @PreUpdate, so the status change has to stamp it itself.
        assertThat(repository.findById(requested.id()).orElseThrow().getUpdatedAt()).isEqualTo(ready.readyAt());
        assertThat(service.downloadLink(ALICE, requested.id()).url()).isEqualTo("https://minio.test/signed");
    }

    @Test
    void oneRequestAtATime() {
        request(ALICE);
        assertThatThrownBy(() -> request(ALICE)).isInstanceOf(DataExportInProgressException.class);
        // Someone else's request is no obstacle.
        assertThat(request(BOB).status()).isEqualTo(DataExportStatus.PROCESSING);
    }

    @Test
    void aNewRequestExpiresTheReadyFile() {
        Long first = request(ALICE).id();
        worker.buildNext();

        request(ALICE);

        assertThat(repository.findById(first).orElseThrow().getStatus()).isEqualTo(DataExportStatus.EXPIRED);
    }

    @Test
    void cancelOnlyWhileProcessing() {
        Long id = request(ALICE).id();

        assertThat(service.cancel(ALICE, id).status()).isEqualTo(DataExportStatus.CANCELED);
        assertThatThrownBy(() -> service.cancel(ALICE, id)).isInstanceOf(DataExportNotCancelableException.class);
        assertThatThrownBy(() -> service.cancel(BOB, id)).isInstanceOf(DataExportNotFoundException.class);

        // Nothing left for the worker.
        worker.buildNext();
        verify(sources, never()).activityOf(any(), anyString());
    }

    @Test
    void aRequestCanceledMidBuildLosesItsFile() throws Exception {
        Long id = request(ALICE).id();
        doAnswer(invocation -> {
            service.cancel(ALICE, id);
            return null;
        }).when(minioClient).putObject(any());

        worker.buildNext();

        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(DataExportStatus.CANCELED);
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void aFailingBuildIsRetriedThenGivenUp() {
        Long id = request(ALICE).id();
        when(sources.activityOf(any(), eq("likes"))).thenThrow(new IllegalStateException("interaction-service down"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            worker.buildNext();
            DataExportRequest row = repository.findById(id).orElseThrow();
            assertThat(row.getAttempts()).isEqualTo(attempt);
            // The lease run out, as if minutes had passed.
            jdbcTemplate.update("update data_export_requests set claimed_at = now() - interval '1 hour' where id = ?", id);
        }

        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(DataExportStatus.FAILED);
    }

    @Test
    void cleanupExpiresOldFilesAndRemovesThem() throws Exception {
        Long id = request(ALICE).id();
        worker.buildNext();
        String key = repository.findById(id).orElseThrow().getObjectKey();
        // Past its four days.
        jdbcTemplate.update("update data_export_requests set expires_at = now() - interval '1 minute' where id = ?", id);
        assertThat(service.list(ALICE).getFirst().status()).isEqualTo(DataExportStatus.EXPIRED);

        cleanup.sweep();

        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(removed.capture());
        assertThat(removed.getValue().object()).isEqualTo(key);
        DataExportRequest row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(DataExportStatus.EXPIRED);
        assertThat(row.getObjectKey()).isNull();
    }

    @Test
    void erasingTheAccountDropsItsRequests() {
        Long id = request(ALICE).id();

        accountStateService.erase(ALICE, Instant.now());

        DataExportRequest row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(DataExportStatus.CANCELED);
        assertThat(row.getDeletedAt()).isNotNull();
        assertThat(service.list(ALICE)).isEmpty();
    }

    @Test
    void theAdminSummaryCountsWhatIsWaitingStalledReadyAndFailed() {
        request(BOB);
        worker.buildNext();
        Long failed = request(ALICE).id();
        when(sources.activityOf(any(), eq("likes"))).thenThrow(new IllegalStateException("interaction-service down"));
        for (int attempt = 1; attempt <= 3; attempt++) {
            worker.buildNext();
            jdbcTemplate.update("update data_export_requests set claimed_at = now() - interval '1 hour' where id = ?", failed);
        }
        Long waiting = request(ALICE).id();
        // Older than three ten-minute leases: no worker is getting through it.
        jdbcTemplate.update("update data_export_requests set created_at = now() - interval '2 hours' where id = ?", waiting);

        DataExportAdminSummaryResponse summary = service.adminSummary();

        assertThat(summary.processing()).isEqualTo(1);
        assertThat(summary.stalled()).isEqualTo(1);
        assertThat(summary.oldestProcessingSince()).isEqualTo(repository.findById(waiting).orElseThrow().getCreatedAt());
        assertThat(summary.readyFiles()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.recentFailures()).singleElement().satisfies(failure -> {
            assertThat(failure.id()).isEqualTo(failed);
            assertThat(failure.userId()).isEqualTo(ALICE);
            assertThat(failure.attempts()).isEqualTo(3);
        });
    }

    private DataExportResponse request(Long userId) {
        return service.request(userId, new RequestDataExportRequest(DataExportFormat.TXT,
                Set.of(DataExportCategory.PROFILE_AND_SETTINGS, DataExportCategory.LIKES_AND_FAVORITES)));
    }
}
