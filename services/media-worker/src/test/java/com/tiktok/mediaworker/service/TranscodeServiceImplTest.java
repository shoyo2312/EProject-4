package com.tiktok.mediaworker.service;

import com.tiktok.event.video.VideoFilter;
import com.tiktok.event.video.VideoPublishedEvent;
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

import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodeServiceImplTest {

    private static final MinioProperties MINIO =
            new MinioProperties("http://localhost:9000", "https://media.example", "key", "secret", "video-media");
    private static final MediaVideoProperties LIMITS =
            new MediaVideoProperties(524_288_000L, 600);
    private static final String RAW_URL = "s3://video-media/raw/7/vid123.mp4";

    @Mock private MinioClient minioClient;
    @Mock private VideoProbe videoProbe;
    @Mock private Ffmpeg ffmpeg;
    @Mock private MusicDownloader musicDownloader;

    private TranscodeServiceImpl service() {
        return new TranscodeServiceImpl(minioClient, MINIO, LIMITS, videoProbe, ffmpeg, musicDownloader);
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

    private void stubAnimatedPreview(boolean succeeds) {
        when(ffmpeg.animatedPreview(any(), any(), anyInt())).thenAnswer(invocation -> {
            if (succeeds) {
                Files.writeString(invocation.getArgument(1), "webp bytes");
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

    /** A real JPEG, because {@code displaySize} reads the frame's header to learn its shape. */
    private static Path jpeg(int width, int height) throws Exception {
        Path file = Files.createTempFile("still", ".jpg");
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg", file.toFile());
        return file;
    }

    @Test
    void displaySize_rotatedCapture_takesOrientationFromTheStillFrame() throws Exception {
        // A portrait phone capture is stored pre-rotation, so the probe reads it as landscape.
        ProbedVideo probed = new ProbedVideo(42, "h264", "aac", 1920, 1080);

        Dimension display = TranscodeServiceImpl.displaySize(probed, jpeg(405, 720));

        assertThat(display).isEqualTo(new Dimension(1080, 1920));
    }

    @Test
    void displaySize_unrotatedLandscape_keepsTheProbedDimensions() throws Exception {
        ProbedVideo probed = new ProbedVideo(42, "h264", "aac", 1920, 1080);

        Dimension display = TranscodeServiceImpl.displaySize(probed, jpeg(1280, 720));

        assertThat(display).isEqualTo(new Dimension(1920, 1080));
    }

    @Test
    void displaySize_withoutAStillFrame_keepsTheProbedDimensions() {
        ProbedVideo probed = new ProbedVideo(42, "h264", "aac", 1920, 1080);

        assertThat(TranscodeServiceImpl.displaySize(probed, null)).isEqualTo(new Dimension(1920, 1080));
    }

    @Test
    void transcode_landscapeUpload_reportsLandscapeDimensions() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        when(ffmpeg.stillFrame(any(), any(), anyInt())).thenAnswer(invocation -> {
            ImageIO.write(new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB), "jpg",
                    ((Path) invocation.getArgument(1)).toFile());
            return true;
        });
        stubAnimatedPreview(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL, null);

        assertThat(result.width()).isEqualTo(1280);
        assertThat(result.height()).isEqualTo(720);
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
        stubAnimatedPreview(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL, null);

        assertThat(result.hlsUrl()).isEqualTo("https://media.example/video-media/hls/vid123/source.mp4");
        assertThat(result.thumbnailUrl()).isEqualTo("https://media.example/video-media/thumbnails/vid123.jpg");
        assertThat(result.previewUrl()).isEqualTo("https://media.example/video-media/previews/vid123.webp");
        assertThat(result.durationSeconds()).isEqualTo(42);

        assertThat(uploads()).extracting(UploadObjectArgs::object)
                .containsExactly("hls/vid123/source.mp4", "thumbnails/vid123.jpg", "previews/vid123.webp");
        assertThat(uploads().get(2).contentType()).isEqualTo("image/webp");
        // One second in, which is where a 42s clip has a frame worth showing.
        verify(ffmpeg).stillFrame(any(), any(), eq(1));
        verify(ffmpeg).animatedPreview(any(), any(), eq(1));
    }

    @Test
    void transcode_containerThatWillNotRemux_storesTheUploadUnchanged() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(false);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL, null);

        // Still playable at the same key — the fallback changes which file is uploaded, not where.
        assertThat(result.hlsUrl()).isEqualTo("https://media.example/video-media/hls/vid123/source.mp4");
        UploadObjectArgs playback = uploads().get(0);
        assertThat(playback.object()).isEqualTo("hls/vid123/source.mp4");
        assertThat(playback.filename()).endsWith("/source");
    }

    @Test
    void transcode_noDecodableFrame_returnsNoArtworkRatherThanFailing() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        stubStillFrame(false);
        stubAnimatedPreview(false);

        TranscodeResult result = service().transcode("vid123", RAW_URL, null);

        assertThat(result.thumbnailUrl()).isNull();
        assertThat(result.previewUrl()).isNull();
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
        stubAnimatedPreview(true);

        service().transcode("vid123", RAW_URL, null);

        verify(ffmpeg).stillFrame(any(), any(), eq(0));
    }

    @Test
    void transcode_removesItsScratchDirectory() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        service().transcode("vid123", RAW_URL, null);

        ArgumentCaptor<DownloadObjectArgs> captor = ArgumentCaptor.forClass(DownloadObjectArgs.class);
        verify(minioClient).downloadObject(captor.capture());
        Path scratch = Path.of(captor.getValue().filename()).getParent();
        assertThat(Files.exists(scratch)).isFalse();
    }

    @Test
    void transcode_fileTooLarge_isRejectedWithoutDownloading() throws Exception {
        stubStat(524_288_001L);

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, null))
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

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, null))
                .isInstanceOf(MediaRejectedException.class)
                .hasMessageContaining("10m");

        verify(minioClient, never()).uploadObject(any());
    }

    @Test
    void transcode_uploadNotInBucket_failsBeforeAnyMinioCall() {
        assertThatThrownBy(() -> service().transcode("vid123", "s3://other-bucket/raw/7/vid123.mp4", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transcode_codecABrowserWillNotPlay_isReEncodedInsteadOfRemuxed() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbeNeedingNormalizing();
        stubNormalize(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL, null);

        assertThat(result.hlsUrl()).isEqualTo("https://media.example/video-media/hls/vid123/source.mp4");
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
        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, null))
                .isInstanceOf(IllegalStateException.class);

        verify(minioClient, never()).uploadObject(any());
    }

    private static VideoPublishedEvent.Edit edit(VideoPublishedEvent.Edit.Segment... segments) {
        return new VideoPublishedEvent.Edit(1, List.of(segments));
    }

    private static VideoPublishedEvent.Edit.Segment segment(double start, double end, double speed) {
        return new VideoPublishedEvent.Edit.Segment(start, end, speed);
    }

    private void stubEdit(boolean succeeds) {
        when(ffmpeg.edit(any(), any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
            if (succeeds) {
                Files.writeString(invocation.getArgument(1), "edited bytes");
            }
            return succeeds;
        });
    }

    /**
     * Everything after the edit — playback, still, preview, and the duration reported back — comes
     * from the edited file, so the moderation that samples them checks what viewers will see.
     */
    @Test
    void transcode_withEdits_buildsEverythingFromTheEditedCut() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        when(videoProbe.probe(any()))
                .thenReturn(new ProbedVideo(42, "h264", "aac", 1280, 720))
                .thenReturn(new ProbedVideo(6, "h264", "aac", 1280, 720));
        stubEdit(true);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL,
                edit(segment(2, 6, 1), segment(10, 14, 2)));

        ArgumentCaptor<Path> edited = ArgumentCaptor.forClass(Path.class);
        verify(ffmpeg).edit(any(), edited.capture(),
                eq(List.of(segment(2, 6, 1), segment(10, 14, 2))), isNull(), eq(List.of()), isNull(), eq(true));
        verify(ffmpeg).faststart(eq(edited.getValue()), any());
        verify(ffmpeg).stillFrame(eq(edited.getValue()), any(), anyInt());
        verify(ffmpeg).animatedPreview(eq(edited.getValue()), any(), anyInt());
        assertThat(result.durationSeconds()).isEqualTo(6);
    }

    @Test
    void transcode_silentUploadWithEdits_editsWithoutAudio() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        when(videoProbe.probe(any())).thenReturn(new ProbedVideo(42, "h264", null, 1280, 720));
        stubEdit(true);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        service().transcode("vid123", RAW_URL, edit(segment(0, 5, 1)));

        verify(ffmpeg).edit(any(), any(), any(), any(), any(), any(), eq(false));
    }

    /**
     * Never the upload in the edit's place: the upload holds the parts the uploader cut out, and
     * those are exactly what they chose not to publish.
     */
    @Test
    void transcode_editThatFails_throwsInsteadOfPublishingTheUncutUpload() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubEdit(false);

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, edit(segment(0, 5, 1))))
                .isInstanceOf(IllegalStateException.class);

        verify(minioClient, never()).uploadObject(any());
    }

    @Test
    void transcode_editThatKeepsNothingOfTheVideo_isRejected() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, edit(segment(50, 60, 1))))
                .isInstanceOf(MediaRejectedException.class);

        verify(ffmpeg, never()).edit(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    /** Opening the editor and touching nothing must not cost a full re-encode. */
    @Test
    void transcode_editThatChangesNothing_skipsTheReencode() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        service().transcode("vid123", RAW_URL, edit(segment(0, 42, 1)));

        verify(ffmpeg, never()).edit(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    /** Slowing a clip down makes it longer, so the limit is checked again on what comes out. */
    @Test
    void transcode_editThatComesOutTooLong_isRejected() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        when(videoProbe.probe(any()))
                .thenReturn(new ProbedVideo(400, "h264", "aac", 1280, 720))
                .thenReturn(new ProbedVideo(800, "h264", "aac", 1280, 720));
        stubEdit(true);

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, edit(segment(0, 400, 0.5))))
                .isInstanceOf(MediaRejectedException.class);

        verify(minioClient, never()).uploadObject(any());
    }

    /** A colour on its own still re-encodes, over the whole upload at normal speed. */
    @Test
    void transcode_editThatOnlyChangesTheColour_filtersTheWholeVideo() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubEdit(true);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        service().transcode("vid123", RAW_URL, new VideoPublishedEvent.Edit(1, List.of(),
                new VideoPublishedEvent.Edit.Filter("mono", 1)));

        ArgumentCaptor<double[]> colour = ArgumentCaptor.forClass(double[].class);
        verify(ffmpeg).edit(any(), any(), eq(List.of(segment(0, 43, 1))), colour.capture(), eq(List.of()), isNull(), eq(true));
        assertThat(colour.getValue()).containsExactly(VideoFilter.MONO.matrix(1));
    }

    private static VideoPublishedEvent.Edit overlayOnly() {
        return new VideoPublishedEvent.Edit(1, List.of(), null,
                List.of(new VideoPublishedEvent.Edit.Overlay("s3://video-media/raw/7/text.png", 1.0, 3.5)));
    }

    /** The overlay is fetched next to the upload, and leaves the source as a real PNG would. */
    private void stubDownloadWithOverlay() throws Exception {
        doAnswer(invocation -> {
            DownloadObjectArgs args = invocation.getArgument(0);
            Path file = Path.of(args.filename());
            if (args.object().endsWith(".png")) {
                ImageIO.write(new BufferedImage(72, 128, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
            } else {
                Files.writeString(file, "downloaded bytes");
            }
            return null;
        }).when(minioClient).downloadObject(any(DownloadObjectArgs.class));
    }

    /** Text on its own still re-encodes, over the whole upload at normal speed. */
    @Test
    void transcode_withAnOverlay_laysTheDownloadedImageOverTheWholeVideo() throws Exception {
        stubStat(1_000_000L);
        stubDownloadWithOverlay();
        stubProbe(42);
        stubEdit(true);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        service().transcode("vid123", RAW_URL, overlayOnly());

        ArgumentCaptor<DownloadObjectArgs> downloads = ArgumentCaptor.forClass(DownloadObjectArgs.class);
        verify(minioClient, org.mockito.Mockito.times(2)).downloadObject(downloads.capture());
        assertThat(downloads.getAllValues()).extracting(DownloadObjectArgs::object)
                .containsExactly("raw/7/vid123.mp4", "raw/7/text.png");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Ffmpeg.Overlay>> overlays = ArgumentCaptor.forClass(List.class);
        verify(ffmpeg).edit(any(), any(), eq(List.of(segment(0, 43, 1))), isNull(), overlays.capture(), isNull(), eq(true));
        assertThat(overlays.getValue()).singleElement().satisfies(overlay -> {
            assertThat(overlay.file().getFileName().toString()).isEqualTo("overlay-0.png");
            assertThat(overlay.start()).isEqualTo(1.0);
            assertThat(overlay.end()).isEqualTo(3.5);
        });
    }

    /**
     * The track is fetched from the URL the event carries and mixed at the levels it names, cut
     * on a silent video at the edited length: 2–6s at 1x plus 10–14s at 2x plays for six seconds.
     */
    @Test
    void transcode_withMusic_downloadsTheTrackAndMixesItAtTheEditedLength() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubEdit(true);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);
        when(musicDownloader.download(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));

        service().transcode("vid123", RAW_URL, new VideoPublishedEvent.Edit(1,
                List.of(segment(2, 6, 1), segment(10, 14, 2)), null, List.of(),
                new VideoPublishedEvent.Edit.Music("https://prod-1.storage.jamendo.com/download/track/42/mp32/",
                        12.5, 0.8, 0.0)));

        verify(musicDownloader).download(eq("https://prod-1.storage.jamendo.com/download/track/42/mp32/"), any());
        ArgumentCaptor<Ffmpeg.Music> music = ArgumentCaptor.forClass(Ffmpeg.Music.class);
        verify(ffmpeg).edit(any(), any(), any(), isNull(), eq(List.of()), music.capture(), eq(true));
        assertThat(music.getValue().file().getFileName().toString()).isEqualTo("music.mp3");
        assertThat(music.getValue().start()).isEqualTo(12.5);
        assertThat(music.getValue().volume()).isEqualTo(0.8);
        assertThat(music.getValue().originalVolume()).isEqualTo(0.0);
        assertThat(music.getValue().editedSeconds()).isEqualTo(6.0);
    }

    /** Music the catalogue no longer has is a failed edit, as any other part of the recipe would be. */
    @Test
    void transcode_musicThatCannotBeFetched_isRejectedNotPublishedSilent() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        when(musicDownloader.download(any(), any()))
                .thenThrow(new MediaRejectedException("The video's music is no longer available."));

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, new VideoPublishedEvent.Edit(1,
                List.of(), null, List.of(), new VideoPublishedEvent.Edit.Music("https://x.jamendo.com/t", 0, 1, 1))))
                .isInstanceOf(MediaRejectedException.class);
        verify(ffmpeg, never()).edit(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void editedSeconds_countsEachSegmentAtItsSpeedUpToTheEndOfTheUpload() {
        assertThat(TranscodeServiceImpl.editedSeconds(List.of(segment(0, 4, 2), segment(8, 20, 1)), 10))
                .isEqualTo(4.0);
    }

    /** Whatever the client uploaded under an image type, ffmpeg only ever decodes a checked PNG. */
    @Test
    void transcode_anOverlayThatIsNotAPng_isRejectedBeforeFfmpegSeesIt() throws Exception {
        stubStat(1_000_000L);
        stubDownload();
        stubProbe(42);

        assertThatThrownBy(() -> service().transcode("vid123", RAW_URL, overlayOnly()))
                .isInstanceOf(MediaRejectedException.class);

        verify(ffmpeg, never()).edit(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(minioClient, never()).uploadObject(any());
    }

    /** The app can be newer than this worker; a colour it cannot name is no reason to fail. */
    @Test
    void transcode_filterThisWorkerDoesNotKnow_publishesWithoutIt() throws Exception {
        stubStat(10_000_000L);
        stubDownload();
        stubProbe(42);
        stubFaststart(true);
        stubStillFrame(true);
        stubAnimatedPreview(true);

        TranscodeResult result = service().transcode("vid123", RAW_URL, new VideoPublishedEvent.Edit(1, List.of(),
                new VideoPublishedEvent.Edit.Filter("from-the-future", 1)));

        verify(ffmpeg, never()).edit(any(), any(), any(), any(), any(), any(), anyBoolean());
        assertThat(result.durationSeconds()).isEqualTo(42);
    }
}
