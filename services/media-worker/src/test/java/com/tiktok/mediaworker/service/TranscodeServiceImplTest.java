package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MediaVideoProperties;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
    private static final String RAW_URL = "s3://video-media/raw/7/vid123.mp4";

    @Mock private MinioClient minioClient;
    @Mock private VideoProbe videoProbe;
    @Mock private Ffmpeg ffmpeg;

    private TranscodeServiceImpl service() {
        return new TranscodeServiceImpl(minioClient, MINIO, LIMITS, videoProbe, ffmpeg);
    }

    private void stubStat(long size) throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.size()).thenReturn(size);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(stat);
    }

    /**
     * UploadObjectArgs refuses to build for a path that is not a regular file, so the mocks have
     * to leave real files behind the way MinIO and ffmpeg would.
     */
    private void stubDownload() throws Exception {
        doAnswer(invocation -> {
            DownloadObjectArgs args = invocation.getArgument(0);
            Files.writeString(Path.of(args.filename()), "downloaded bytes");
            return null;
        }).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
    }

    private void stubProbe(int durationSeconds) {
        // h264/aac at 720p: already browser-ready, so the cheap remux path is the default here.
        when(videoProbe.probe(any())).thenReturn(new ProbedVideo(durationSeconds, "h264", "aac", 1280, 720));
    }

    private void stubProbeNeedingNormalizing() {
        when(videoProbe.probe(any())).thenReturn(new ProbedVideo(42, "hevc", "aac", 1920, 1080));
    }

    private void stubNormalize(boolean succeeds) {
        when(ffmpeg.normalize(any(), any())).thenAnswer(invocation -> {
            if (succeeds) {
                Files.writeString(invocation.getArgument(1), "re-encoded bytes");
            }
            return succeeds;
        });
    }

    private void stubFaststart(boolean succeeds) {
        when(ffmpeg.faststart(any(), any())).thenAnswer(invocation -> {
            if (succeeds) {
                Files.writeString(invocation.getArgument(1), "remuxed bytes");
            }
            return succeeds;
        });
    }

    private void stubStillFrame(boolean succeeds) {
        when(ffmpeg.stillFrame(any(), any(), anyInt())).thenAnswer(invocation -> {
            if (succeeds) {
                Files.writeString(invocation.getArgument(1), "jpeg bytes");
            }
            return succeeds;
        });
    }

    private List<UploadObjectArgs> uploads() throws Exception {
        ArgumentCaptor<UploadObjectArgs> captor = ArgumentCaptor.forClass(UploadObjectArgs.class);
        verify(minioClient, atLeastOnce()).uploadObject(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void transcode_withinLimits_uploadsFaststartPlaybackAndThumbnail() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        stubStillFrame(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL);

        assertThat(result.hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/vid123/source.mp4");
        assertThat(result.thumbnailUrl()).isEqualTo("http://localhost:9000/video-media/thumbnails/vid123.jpg");
        assertThat(result.durationSeconds()).isEqualTo(42);

        assertThat(uploads()).extracting(UploadObjectArgs::object)
                .containsExactly("hls/vid123/source.mp4", "thumbnails/vid123.jpg");
        // One second in, which is where a 42s clip has a frame worth showing.
        verify(ffmpeg).stillFrame(any(), any(), eq(1));
    }

    @Test
    void transcode_containerThatWillNotRemux_storesTheUploadUnchanged() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(false);
        stubStillFrame(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL);

        // Still playable at the same key — the fallback changes which file is uploaded, not where.
        assertThat(result.hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/vid123/source.mp4");
        UploadObjectArgs playback = uploads().get(0);
        assertThat(playback.object()).isEqualTo("hls/vid123/source.mp4");
        assertThat(playback.filename()).endsWith("/source");
    }

    @Test
    void transcode_noDecodableFrame_returnsNoThumbnailRatherThanFailing() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        stubStillFrame(false);

        TranscodeResult result = service().transcode("vid123", RAW_URL);

        assertThat(result.thumbnailUrl()).isNull();
        assertThat(result.hlsUrl()).isNotNull();
        assertThat(uploads()).extracting(UploadObjectArgs::object).containsExactly("hls/vid123/source.mp4");
    }

    @Test
    void transcode_shortClip_takesTheFrameBeforeTheHalfwayMark() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(1);
        stubFaststart(true);
        stubStillFrame(true);

        service().transcode("vid123", RAW_URL);

        verify(ffmpeg).stillFrame(any(), any(), eq(0));
    }

    @Test
    void transcode_removesItsScratchDirectory() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        stubStillFrame(true);

        service().transcode("vid123", RAW_URL);

        ArgumentCaptor<DownloadObjectArgs> captor = ArgumentCaptor.forClass(DownloadObjectArgs.class);
        verify(minioClient).downloadObject(captor.capture());
        Path scratch = Path.of(captor.getValue().filename()).getParent();
        assertThat(Files.exists(scratch)).isFalse();
    }

    @Test
    void transcode_fileTooLarge_isRejectedWithoutDownloading() throws Exception {
        stubStat(524_288_001L);

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining("500 MB");

        verify(minioClient, never()).downloadObject(any());
        verify(minioClient, never()).uploadObject(any());
    }

    @Test
    void transcode_tooLong_isRejectedWithoutUploading() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(601);

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining("10m");

        verify(minioClient, never()).uploadObject(any());
    }

    @Test
    void transcode_uploadNotInBucket_failsBeforeAnyMinioCall() {
        assertThatThrownBy(() -> service().transcode("vid123", "s3://other-bucket/raw/7/vid123.mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transcode_codecABrowserWillNotPlay_isReEncodedInsteadOfRemuxed() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbeNeedingNormalizing();
        stubNormalize(true);
        stubStillFrame(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL);

        assertThat(result.hlsUrl()).isEqualTo("http://localhost:9000/video-media/hls/vid123/source.mp4");
        verify(ffmpeg, never()).faststart(any(), any());
        assertThat(uploads().get(0).filename()).endsWith("/playback.mp4");
    }

    @Test
    void transcode_normalizeFails_isReportedRatherThanStoringAnUnplayableFile() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbeNeedingNormalizing();
        stubNormalize(false);

        // Falling back here would put an HEVC file at the playback key and hand the viewer a
        // video that silently does not play.
        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL))
                .isInstanceOf(IllegalStateException.class);

        verify(minioClient, never()).uploadObject(any());
    }
}
