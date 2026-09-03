package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MediaVideoProperties;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeServiceImplTest {

    private static final MinioProperties MINIO =
            new MinioProperties("http://localhost:9000", "key", "secret", "video-media");
    private static final MediaVideoProperties LIMITS =
            new MediaVideoProperties(524_288_000L, 600);

    @Mock private MinioClient minioClient;
    @Mock private VideoProbe videoProbe;

    private TranscodeServiceImpl service() {
        return new TranscodeServiceImpl(minioClient, MINIO, LIMITS, videoProbe);
    }

    private void stubStat(long size) throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(size);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
    }

    private void stubPresignedGet() throws Exception {
        lenient().when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/video-media/raw/7/vid123.mp4?sig=x");
    }

    @Test
    void transcode_withinLimits_copiesAndReturnsMeasuredDuration() throws Exception {
        stubStat(10_000_000L);
        stubPresignedGet();
        when(videoProbe.durationSeconds(any())).thenReturn(42);

        TranscodeResult result = service().transcode("vid123", "s3://video-media/raw/7/vid123.mp4");

        assertThat(result.hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/vid123/source.mp4");
        assertThat(result.durationSeconds()).isEqualTo(42);

        ArgumentCaptor<CopyObjectArgs> captor = ArgumentCaptor.forClass(CopyObjectArgs.class);
        verify(minioClient).copyObject(captor.capture());
        assertThat(captor.getValue().object()).isEqualTo("hls/vid123/source.mp4");
    }

    @Test
    void transcode_fileTooLarge_isRejectedWithoutCopying() throws Exception {
        stubStat(524_288_001L);

        assertThatThrownBy(() -> service().transcode("vid123", "s3://video-media/raw/7/vid123.mp4"))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining("500 MB");

        verify(minioClient, never()).copyObject(any());
    }

    @Test
    void transcode_tooLong_isRejectedWithoutCopying() throws Exception {
        stubStat(10_000_000L);
        stubPresignedGet();
        when(videoProbe.durationSeconds(any())).thenReturn(601);

        assertThatThrownBy(() -> service().transcode("vid123", "s3://video-media/raw/7/vid123.mp4"))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining("10m");

        verify(minioClient, never()).copyObject(any());
    }

    @Test
    void transcode_uploadNotInBucket_failsBeforeAnyMinioCall() {
        assertThatThrownBy(() -> service().transcode("vid123", "s3://other-bucket/raw/7/vid123.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
