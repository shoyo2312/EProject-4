package com.tiktok.storyservice.service;

import com.tiktok.storyservice.config.MinioConfig;
import com.tiktok.storyservice.config.MinioProperties;
import com.tiktok.storyservice.config.StoryUploadLimits;
import com.tiktok.storyservice.dto.response.StoryUploadUrlResponse;
import com.tiktok.storyservice.exception.UploadNotFoundException;
import com.tiktok.storyservice.exception.UploadUrlUnavailableException;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Uploads through the presigned POST form the browser gets, against a real MinIO: what the form
 * still lets its holder do after the story is posted is MinIO's behaviour, not ours.
 */
@Testcontainers
class StoryMediaIntegrationTest {

    private static final String BUCKET = "video-media";

    @Container
    // minio/minio:latest 404s on Docker Hub — MinIO stopped publishing community images; use the pgsty fork.
    static MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z").asCompatibleSubstituteFor("minio/minio"));

    private static MinioClient minioClient;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void createBucket() throws Exception {
        minioClient = new MinioConfig().minioClient(properties(MINIO.getUserName(), MINIO.getPassword()));
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    /**
     * The form stays good for minio.url-expiry after it was issued, so its holder can upload again
     * once the story is approved. Served from the upload key, the story would then show what nobody
     * screened.
     */
    @Test
    void uploadingAgainThroughTheFormLeavesTheStoryWithWhatWasScreened() throws Exception {
        StoryMedia media = media(MINIO.getUserName(), MINIO.getPassword());
        StoryUploadUrlResponse upload = media.presignUpload(7L, "image/jpeg");
        assertThat(post(upload, "screened")).isEqualTo(204);
        String uploadKey = media.keyOf(7L, upload.fileUrl());

        String storyKey = media.copyForStory(uploadKey, "55");
        assertThat(post(upload, "swapped")).as("the form still works").isEqualTo(204);

        assertThat(read(uploadKey)).isEqualTo("swapped");
        assertThat(read(storyKey)).isEqualTo("screened");
    }

    @Test
    void copyOfAnUploadThatIsNotThereIsUploadNotFound() {
        StoryMedia media = media(MINIO.getUserName(), MINIO.getPassword());

        assertThatThrownBy(() -> media.copyForStory("stories/7/404.jpg", "56"))
                .isInstanceOf(UploadNotFoundException.class);
    }

    /** Storage refusing is not the user's upload going missing: "upload again" would not help. */
    @Test
    void copyThatStorageRefusesIsUnavailable() {
        StoryMedia media = media("nobody", "not-the-secret");

        assertThatThrownBy(() -> media.copyForStory("stories/7/1.jpg", "57"))
                .isInstanceOf(UploadUrlUnavailableException.class);
    }

    private static MinioProperties properties(String accessKey, String secretKey) {
        return new MinioProperties(MINIO.getS3URL(), MINIO.getS3URL(), accessKey, secretKey, BUCKET, "us-east-1",
                Duration.ofMinutes(15));
    }

    private static StoryMedia media(String accessKey, String secretKey) {
        MinioProperties properties = properties(accessKey, secretKey);
        return new StoryMedia(new MinioConfig().minioClient(properties), properties,
                new StoryUploadLimits(10_485_760L, 52_428_800L));
    }

    /** What the browser sends: the signed fields, then the file, which S3 wants last. */
    private int post(StoryUploadUrlResponse upload, String content) throws Exception {
        String boundary = "story-upload";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : upload.formFields().entrySet()) {
            body.writeBytes(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n")
                    .formatted(boundary, field.getKey(), field.getValue()).getBytes(UTF_8));
        }
        body.writeBytes(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"story.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n%s\r\n--%s--\r\n").formatted(boundary, content, boundary).getBytes(UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(upload.uploadUrl()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String read(String key) throws Exception {
        try (InputStream object = minioClient.getObject(GetObjectArgs.builder().bucket(BUCKET).object(key).build())) {
            return new String(object.readAllBytes(), UTF_8);
        }
    }
}
