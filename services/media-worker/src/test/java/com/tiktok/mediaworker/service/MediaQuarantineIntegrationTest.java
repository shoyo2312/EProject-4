package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.BucketInitializer;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
    static MinIOContainer MINIO = new MinIOContainer("minio/minio:latest");

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
    void quarantine_takesEveryTranscodedObjectOffTheAnonymousPath() throws Exception {
        put("hls/v1/source.mp4");
        put("thumbnails/v1.jpg");
        put("previews/v1.webp");
        assertThat(anonymousStatus("hls/v1/source.mp4")).isEqualTo(200);

        quarantine.quarantine("v1");

        assertThat(anonymousStatus("hls/v1/source.mp4")).isNotEqualTo(200);
        assertThat(anonymousStatus("thumbnails/v1.jpg")).isNotEqualTo(200);
        assertThat(anonymousStatus("previews/v1.webp")).isNotEqualTo(200);
        assertThat(anonymousStatus("quarantine/hls/v1/source.mp4")).isNotEqualTo(200);
        // Kept, not deleted: an admin may overturn the decision.
        assertThat(exists("quarantine/hls/v1/source.mp4")).isTrue();
        assertThat(quarantine.isQuarantined("v1")).isTrue();
    }

    @Test
    void release_putsTheMediaBackWhereThePlayerLooksForIt() throws Exception {
        put("hls/v2/source.mp4");
        put("thumbnails/v2.jpg");
        quarantine.quarantine("v2");

        quarantine.release("v2");

        assertThat(anonymousStatus("hls/v2/source.mp4")).isEqualTo(200);
        assertThat(anonymousStatus("thumbnails/v2.jpg")).isEqualTo(200);
        assertThat(exists("quarantine/hls/v2/source.mp4")).isFalse();
        assertThat(quarantine.isQuarantined("v2")).isFalse();
    }

    /** Redelivered events and a takedown that lands before the transcode both call it twice or early. */
    @Test
    void quarantine_isSafeToRepeatAndToRunBeforeAnyMediaExists() throws Exception {
        assertThatCode(() -> quarantine.quarantine("v3")).doesNotThrowAnyException();
        assertThat(quarantine.isQuarantined("v3")).isTrue();

        put("hls/v3/source.mp4");
        quarantine.quarantine("v3");
        quarantine.quarantine("v3");

        assertThat(anonymousStatus("hls/v3/source.mp4")).isNotEqualTo(200);
        assertThat(exists("quarantine/hls/v3/source.mp4")).isTrue();
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
