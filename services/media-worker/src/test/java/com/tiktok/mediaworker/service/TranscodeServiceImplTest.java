package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.CopyObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TranscodeServiceImplTest {

    private static final MinioProperties PROPERTIES =
            new MinioProperties("http://localhost:9000", "key", "secret", "video-media");

    @Mock
    private MinioClient minioClient;

    @Test
    void transcode_copiesTheUploadToItsPlaybackKey_andReturnsThatUrl() throws Exception {
        TranscodeServiceImpl service = new TranscodeServiceImpl(minioClient, PROPERTIES);

        TranscodeResult result = service.transcode("vid123", "s3://video-media/raw/7/vid123.mp4");

        assertThat(result.hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/vid123/source.mp4");
        // Both need the file decoded, which this pipeline does not do — the client falls back.
        assertThat(result.thumbnailUrl()).isNull();
        assertThat(result.durationSeconds()).isNull();

        ArgumentCaptor<CopyObjectArgs> captor = ArgumentCaptor.forClass(CopyObjectArgs.class);
        verify(minioClient).copyObject(captor.capture());
        assertThat(captor.getValue().object()).isEqualTo("hls/vid123/source.mp4");
        assertThat(captor.getValue().source().object()).isEqualTo("raw/7/vid123.mp4");
    }

    /**
     * Failing beats copying a guessed key: the consumer retries and then reports FAILED, which is
     * the truth, where a wrong key would publish a video pointing at somebody else's object.
     */
    @Test
    void transcode_failsWhenTheUploadIsNotInTheBucket() {
        TranscodeServiceImpl service = new TranscodeServiceImpl(minioClient, PROPERTIES);

        assertThatThrownBy(() -> service.transcode("vid123", "s3://other-bucket/raw/7/vid123.mp4"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(minioClient);
    }
}
