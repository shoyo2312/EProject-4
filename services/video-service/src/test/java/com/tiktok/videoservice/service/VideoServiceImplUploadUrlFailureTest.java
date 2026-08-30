package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.exception.UploadUrlUnavailableException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito, no Spring context: the only thing under test is what a failing presign turns
 * into. Without the mapping the checked exceptions minio-java declares would escape as a bare
 * 500, which is the same answer a client gets for a genuine bug in this service.
 */
class VideoServiceImplUploadUrlFailureTest {

    @Test
    void createUploadUrl_whenSigningFails_isServiceUnavailableNotABare500() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new io.minio.errors.ServerException("storage said no", 500, null));

        VideoService videoService = new VideoServiceImpl(
                mock(VideoRepository.class),
                mock(VideoMapper.class),
                new SpringDataWebProperties(),
                minioClient,
                new MinioProperties("http://localhost:9000", "key", "secret",
                        "video-media", "us-east-1", Duration.ofMinutes(15)),
                mock(VideoCache.class),
                mock(com.tiktok.videoservice.client.FriendshipClient.class));

        assertThatThrownBy(() -> videoService.createUploadUrl(1L, new UploadUrlRequest("video/mp4")))
                .isInstanceOf(UploadUrlUnavailableException.class)
                .satisfies(e -> assertThat(((UploadUrlUnavailableException) e).getStatus())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                // the message reaches the client; the endpoint and bucket must not ride along
                .hasMessageNotContaining("localhost")
                .hasMessageNotContaining("video-media");
    }
}
