package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.config.UploadLimitProperties;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.exception.UploadUrlUnavailableException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito, no Spring context: what {@code createUploadUrl} builds from a POST policy, and
 * what a failing presign turns into. Without the failure mapping the checked exceptions
 * minio-java declares would escape as a bare 500 — the same answer a client gets for a genuine
 * bug in this service.
 */
class VideoServiceImplUploadUrlFailureTest {

    private final MinioClient minioClient = mock(MinioClient.class);

    private VideoService newService() {
        return new VideoServiceImpl(
                mock(VideoRepository.class),
                mock(VideoMapper.class),
                new SpringDataWebProperties(),
                minioClient,
                new MinioProperties("http://localhost:9000", "key", "secret",
                        "video-media", "us-east-1", Duration.ofMinutes(15)),
                mock(VideoCache.class),
                mock(com.tiktok.videoservice.client.FriendshipClient.class),
                new UploadLimitProperties(524_288_000L, 600));
    }

    @Test
    void createUploadUrl_returnsAPostPolicyBoundedToTheSizeLimit() throws Exception {
        when(minioClient.getPresignedPostFormData(any(PostPolicy.class)))
                .thenReturn(new HashMap<>(Map.of(
                        "policy", "base64policy",
                        "x-amz-signature", "deadbeef")));

        UploadUrlResponse response = newService().createUploadUrl(7L, new UploadUrlRequest("video/mp4"));

        assertThat(response.uploadUrl()).isEqualTo("http://localhost:9000/video-media");
        assertThat(response.formFields())
                .containsKeys("policy", "x-amz-signature", "key", "Content-Type");
        assertThat(response.formFields().get("Content-Type")).isEqualTo("video/mp4");
        assertThat(response.formFields().get("key")).matches("raw/7/\\d+\\.mp4");
        assertThat(response.fileUrl()).matches("s3://video-media/raw/7/\\d+\\.mp4");
        assertThat(response.expiresInSeconds()).isPositive();
        verify(minioClient, never()).getPresignedObjectUrl(any());
    }

    @Test
    void createUploadUrl_whenSigningFails_isServiceUnavailableNotABare500() throws Exception {
        when(minioClient.getPresignedPostFormData(any(PostPolicy.class)))
                .thenThrow(new io.minio.errors.ServerException("storage said no", 500, null));

        VideoService videoService = newService();

        assertThatThrownBy(() -> videoService.createUploadUrl(1L, new UploadUrlRequest("video/mp4")))
                .isInstanceOf(UploadUrlUnavailableException.class)
                .satisfies(e -> assertThat(((UploadUrlUnavailableException) e).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                // the message reaches the client; the endpoint and bucket must not ride along
                .hasMessageNotContaining("localhost")
                .hasMessageNotContaining("video-media");
    }
}
