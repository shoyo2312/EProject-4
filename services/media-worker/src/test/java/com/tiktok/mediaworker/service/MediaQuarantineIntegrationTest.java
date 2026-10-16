package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.BucketInitializer;
import com.tiktok.mediaworker.config.MinioProperties;
import com.tiktok.mediaworker.service.MediaQuarantineService.Hold;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * What an anonymous viewer can fetch, asked of a real MinIO with the real bucket policy.
 *
 * <p>The transcoded media is public by URL because players follow those URLs without credentials.
 * That used to hold for a video the classifier rejected or an admin took down as well: its status
 * hid it from every listing, but anyone holding the URL — video ids are Snowflakes, so guessable —
 * could still play it.
 */
@Testcontainers
class MediaQuarantineIntegrationTest {

    private static final String BUCKET = "video-media";

    @Container
    // minio/minio:latest 404s on Docker Hub — MinIO stopped publishing community images; use the pgsty fork.
    static MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z").asCompatibleSubstituteFor("minio/minio"));

    private final HttpClient http = HttpClient.newHttpClient();
    private MinioClient minioClient;
    private MediaQuarantineService quarantine;

    @BeforeEach
    void setUp() throws Exception {
        MinioProperties properties = new MinioProperties(
                MINIO.getS3URL(), MINIO.getUserName(), MINIO.getPassword(), BUCKET);
        minioClient = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
        new BucketInitializer(minioClient, properties).run(null);
        quarantine = new MediaQuarantineService(minioClient, properties);
    }

    /**
     * The hover preview is written next to the thumbnail and its URL is stored on the video, but
     * the policy was written before previews existed and never listed their prefix: every preview
     * answered 403 and the client fell back to the still frame without saying why.
     */
    @Test
    void everyTranscodeOutputTheClientIsGiven_isReadableWithoutCredentials() throws Exception {
        put("hls/v0/source.mp4");
        put("thumbnails/v0.jpg");
        put("previews/v0.webp");

        assertThat(anonymousStatus("hls/v0/source.mp4")).isEqualTo(200);
        assertThat(anonymousStatus("thumbnails/v0.jpg")).isEqualTo(200);
        assertThat(anonymousStatus("previews/v0.webp")).isEqualTo(200);
    }

    /** raw/ holds the users' originals, reached only through a presigned upload. */
    @Test
    void rawUploads_stayPrivate() throws Exception {
        put("raw/7/v0.mp4");

        assertThat(anonymousStatus("raw/7/v0.mp4")).isNotEqualTo(200);
    }

    @Test
    void hold_takesEveryTranscodedObjectOffTheAnonymousPath() throws Exception {
        put("hls/v1/source.mp4");
        put("thumbnails/v1.jpg");
        put("previews/v1.webp");
        assertThat(anonymousStatus("hls/v1/source.mp4")).isEqualTo(200);

        quarantine.hold("v1", Hold.TAKEN_DOWN);

        assertThat(anonymousStatus("hls/v1/source.mp4")).isNotEqualTo(200);
        assertThat(anonymousStatus("thumbnails/v1.jpg")).isNotEqualTo(200);
        assertThat(anonymousStatus("previews/v1.webp")).isNotEqualTo(200);
        assertThat(anonymousStatus("quarantine/hls/v1/source.mp4")).isNotEqualTo(200);
        // Kept, not deleted: an admin may overturn the decision.
        assertThat(exists("quarantine/hls/v1/source.mp4")).isTrue();
        assertThat(quarantine.isQuarantined("v1")).isTrue();
    }

    @Test
    void restore_putsTheMediaBackWhereThePlayerLooksForIt() throws Exception {
        put("hls/v2/source.mp4");
        put("thumbnails/v2.jpg");
        quarantine.hold("v2", Hold.TAKEN_DOWN);

        quarantine.restore("v2");

        assertThat(anonymousStatus("hls/v2/source.mp4")).isEqualTo(200);
        assertThat(anonymousStatus("thumbnails/v2.jpg")).isEqualTo(200);
        assertThat(exists("quarantine/hls/v2/source.mp4")).isFalse();
        assertThat(quarantine.isQuarantined("v2")).isFalse();
    }

    /** Redelivered events and a takedown that lands before the transcode both call it twice or early. */
    @Test
    void hold_isSafeToRepeatAndToRunBeforeAnyMediaExists() throws Exception {
        assertThatCode(() -> quarantine.hold("v3", Hold.TAKEN_DOWN)).doesNotThrowAnyException();
        assertThat(quarantine.isQuarantined("v3")).isTrue();

        put("hls/v3/source.mp4");
        quarantine.enforce("v3");
        quarantine.hold("v3", Hold.TAKEN_DOWN);

        assertThat(anonymousStatus("hls/v3/source.mp4")).isNotEqualTo(200);
        assertThat(exists("quarantine/hls/v3/source.mp4")).isTrue();
    }

    @Test
    void enforce_withNothingHoldingTheVideo_leavesTheMediaPublic() throws Exception {
        put("hls/v4/source.mp4");

        quarantine.enforce("v4");

        assertThat(anonymousStatus("hls/v4/source.mp4")).isEqualTo(200);
    }

    /**
     * A video held for review and then taken down goes back to review when the takedown is lifted,
     * as it does in video-service. With one marker for both, lifting the takedown published the
     * media of a video nobody had cleared; only the second restore, the approval, may do that.
     */
    @Test
    void restore_liftsTheTakedownOnly_andTheApprovalReleases() throws Exception {
        put("hls/v6/source.mp4");
        quarantine.hold("v6", Hold.MODERATION);
        quarantine.hold("v6", Hold.TAKEN_DOWN);

        quarantine.restore("v6");
        assertThat(anonymousStatus("hls/v6/source.mp4")).isNotEqualTo(200);

        quarantine.restore("v6");
        assertThat(anonymousStatus("hls/v6/source.mp4")).isEqualTo(200);
    }

    /** No restore undoes a deletion: the media waits in quarantine for the purge. */
    @Test
    void restore_neverReleasesADeletedVideo() throws Exception {
        put("hls/v7/source.mp4");
        quarantine.hold("v7", Hold.TAKEN_DOWN);
        quarantine.hold("v7", Hold.DELETED);

        quarantine.restore("v7");
        quarantine.restore("v7");

        assertThat(anonymousStatus("hls/v7/source.mp4")).isNotEqualTo(200);
        assertThat(exists("quarantine/hls/v7/source.mp4")).isTrue();
    }

    /** A video quarantined before holds were recorded still counts as held, and a restore lifts it. */
    @Test
    void theMarkerFromBeforeHolds_isStillHonoured() throws Exception {
        put("quarantine/hls/v8/source.mp4");
        put(MediaKeys.quarantineMarker("v8"));
        put("hls/v8/late.ts");

        assertThat(quarantine.isQuarantined("v8")).isTrue();
        quarantine.enforce("v8");
        assertThat(anonymousStatus("hls/v8/late.ts")).isNotEqualTo(200);

        quarantine.restore("v8");

        assertThat(anonymousStatus("hls/v8/source.mp4")).isEqualTo(200);
        assertThat(quarantine.isQuarantined("v8")).isFalse();
    }

    private void put(String key) throws Exception {
        byte[] body = "media".getBytes();
        minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
                .stream(new ByteArrayInputStream(body), body.length, -1).build());
    }

    private boolean exists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(key).build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int anonymousStatus(String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(MINIO.getS3URL() + "/" + BUCKET + "/" + key)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** The redelivery guard against a real bucket: absent until recorded, private once it is. */
    @Test
    void transcodeMarker_isAbsentUntilRecordedAndNeverPublic() throws Exception {
        TranscodeServiceImpl transcoder = new TranscodeServiceImpl(minioClient,
                new MinioProperties(MINIO.getS3URL(), MINIO.getUserName(), MINIO.getPassword(), BUCKET),
                null, null, null);

        assertThat(transcoder.alreadyTranscoded("v5")).isFalse();
        transcoder.recordTranscoded("v5");
        assertThat(transcoder.alreadyTranscoded("v5")).isTrue();
        assertThat(anonymousStatus(MediaKeys.transcodedMarker("v5"))).isEqualTo(403);
    }
}
