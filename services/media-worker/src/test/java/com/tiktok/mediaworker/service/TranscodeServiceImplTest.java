package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TranscodeServiceImplTest {

    @Mock
    private MinioClient minioClient;

    @Test
    void transcode_uploadsThumbnailAndHlsPlaceholders_andReturnsDeterministicUrls() throws Exception {
        MinioProperties properties = new MinioProperties("http://localhost:9000", "key", "secret", "video-media");
        TranscodeServiceImpl service = new TranscodeServiceImpl(minioClient, properties);

        TranscodeResult result = service.transcode("vid123", "s3://raw/vid123.mp4");

        assertThat(result.thumbnailUrl()).isEqualTo("http://localhost:9000/video-media/thumbnails/vid123.jpg");
        assertThat(result.hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/vid123/master.m3u8");
        assertThat(result.durationSeconds()).isPositive();

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient, times(2)).putObject(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(PutObjectArgs::object)
                .containsExactly("thumbnails/vid123.jpg", "hls/vid123/master.m3u8");
    }
}
