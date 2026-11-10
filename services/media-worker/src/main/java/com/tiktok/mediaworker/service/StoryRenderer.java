package com.tiktok.mediaworker.service;

import com.tiktok.common.storage.ObjectKeys;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Applies a story's edits — the same recipe a video's publication carries — before the story is
 * screened, so what moderation looks at is what viewers are served.
 *
 * <p>A video story goes through {@link Ffmpeg#edit} as a video does. A photo is first made a short
 * still clip so it can take the same path: one with a song comes back that clip, as a song needs a
 * timeline to play on; one without stays a picture, its first frame with the colour, text and
 * stickers drawn in.
 *
 * <p>The result sits next to the story's own copy, {@code stories/{userId}/{storyId}/rendered.*},
 * which no upload policy signs. The copy as posted stays for the lifecycle rule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryRenderer {

    /** Longer than any story may play: the edit can only cut a clip down, or slow part of it. */
    static final int MAX_SOURCE_SECONDS = 180;

    /** video-service's photo bound: an image with a song plays 1 to 15 seconds. */
    static final double MAX_PHOTO_SECONDS = 15;

    /** How long a photo without a song is held for: only its first frame is kept. */
    private static final double STILL_SECONDS = 1;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final VideoProbe videoProbe;
    private final Ffmpeg ffmpeg;
    private final MusicDownloader musicDownloader;

    /**
     * @param mediaKey where the rendered story was stored
     * @param image    whether it is a picture; a photo with a song comes back a video
     */
    public record Rendered(String mediaKey, boolean image) {
    }

    /**
     * Null when there is nothing to render — no edit, or one that changes nothing — and the story
     * is screened and served as posted.
     *
     * @throws MediaRejectedException when the edit cannot be applied to this media for good
     * @throws Exception              for trouble that may pass, such as storage not answering
     */
    public Rendered render(String storyId, String mediaKey, boolean image, VideoPublishedEvent.Edit edit,
                           Double photoSeconds) throws Exception {
        if (edit == null || !changesAnything(edit, image)) {
            return null;
        }
        Path work = Files.createTempDirectory("story-render-" + storyId + "-");
        try {
            Path source = work.resolve("source" + mediaKey.substring(mediaKey.lastIndexOf('.')));
            download(mediaKey, source);

            boolean withSong = edit.music() != null;
            List<VideoPublishedEvent.Edit.Segment> segments;
            boolean withAudio;
            int sourceSeconds;
            if (image) {
                double seconds = withSong ? clampPhotoSeconds(photoSeconds) : STILL_SECONDS;
                Path still = work.resolve("still.mp4");
                if (!ffmpeg.stillVideo(source, still, seconds)) {
                    throw new MediaRejectedException("The story's picture could not be read.");
                }
                source = still;
                segments = List.of(new VideoPublishedEvent.Edit.Segment(0, seconds, 1.0));
                withAudio = false;
                sourceSeconds = (int) Math.ceil(seconds);
            } else {
                ProbedVideo probed = videoProbe.probe(source.toUri().toString());
                if (probed.durationSeconds() > MAX_SOURCE_SECONDS) {
                    throw new MediaRejectedException("The story's video is longer than %d seconds."
                            .formatted(MAX_SOURCE_SECONDS));
                }
                segments = EditFilterGraph.usable(edit, probed.durationSeconds());
                if (segments.isEmpty()) {
                    throw new MediaRejectedException("The edit keeps nothing of the story.");
                }
                withAudio = probed.audioCodec() != null;
                sourceSeconds = probed.durationSeconds();
            }

            double[] colour = EditFilterGraph.colour(edit).orElse(null);
            if (edit.overlays().size() > TranscodeServiceImpl.MAX_OVERLAYS) {
                throw new MediaRejectedException("The edit has %d text and sticker layers; the maximum is %d."
                        .formatted(edit.overlays().size(), TranscodeServiceImpl.MAX_OVERLAYS));
            }
            List<Ffmpeg.Overlay> overlays = new ArrayList<>();
            for (int i = 0; i < edit.overlays().size(); i++) {
                VideoPublishedEvent.Edit.Overlay overlay = edit.overlays().get(i);
                overlays.add(new Ffmpeg.Overlay(downloadOverlay(overlay, work.resolve("overlay-" + i + ".png")),
                        // A still picture has no timeline: its layers show throughout.
                        image && !withSong ? null : overlay.start(), image && !withSong ? null : overlay.end()));
            }
            Ffmpeg.Music music = null;
            if (withSong) {
                VideoPublishedEvent.Edit.Music track = edit.music();
                music = new Ffmpeg.Music(musicDownloader.download(track.audioUrl(), work.resolve("music.mp3")),
                        Math.max(0, track.start()), clamp(track.volume()), clamp(track.originalVolume()),
                        TranscodeServiceImpl.editedSeconds(segments, sourceSeconds));
            }

            Path edited = work.resolve("edited.mp4");
            log.info("Rendering story {} ({}){}{}{}", storyId, image ? "photo" : "video",
                    colour == null ? "" : " with filter " + edit.filter().id(),
                    overlays.isEmpty() ? "" : " with " + overlays.size() + " text and sticker layer(s)",
                    music == null ? "" : " with music");
            if (!ffmpeg.edit(source, edited, segments, colour, overlays, music, withAudio)) {
                throw new IllegalStateException("Could not apply the edits to story " + storyId);
            }

            String folder = mediaKey.substring(0, mediaKey.lastIndexOf('/') + 1);
            if (image && !withSong) {
                Path picture = work.resolve("rendered.jpg");
                if (!ffmpeg.firstFrame(edited, picture)) {
                    throw new IllegalStateException("Could not draw the edits into story " + storyId);
                }
                String key = folder + "rendered.jpg";
                upload(key, picture, "image/jpeg");
                return new Rendered(key, true);
            }
            String key = folder + "rendered.mp4";
            upload(key, edited, "video/mp4");
            return new Rendered(key, false);
        } finally {
            deleteRecursively(work);
        }
    }

    /** On a photo the cuts mean nothing; anything else is a change worth a render. */
    static boolean changesAnything(VideoPublishedEvent.Edit edit, boolean image) {
        if (image) {
            return edit.filter() != null || !edit.overlays().isEmpty() || edit.music() != null;
        }
        return EditFilterGraph.changesAnything(edit, Double.MAX_VALUE);
    }

    static double clampPhotoSeconds(Double seconds) {
        if (seconds == null || seconds.isNaN()) {
            return 6;
        }
        return Math.min(MAX_PHOTO_SECONDS, Math.max(1, seconds));
    }

    private static double clamp(double volume) {
        return Math.min(1, Math.max(0, volume));
    }

    /** Checked as a video's overlay is: story-service confirmed whose PNG it is, but the bytes are the client's. */
    private Path downloadOverlay(VideoPublishedEvent.Edit.Overlay overlay, Path file) throws Exception {
        String bucket = minioProperties.bucket();
        String key = ObjectKeys.objectKey(overlay.imageUrl(), bucket).orElseThrow(() -> new MediaRejectedException(
                "A text and sticker layer of the story is not in bucket " + bucket));
        long sizeBytes = minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
        if (sizeBytes > OverlayImage.MAX_BYTES) {
            throw new MediaRejectedException("A text and sticker layer of the story is too large.");
        }
        download(key, file);
        OverlayImage.requireUsable(file);
        return file;
    }

    private void download(String key, Path target) throws Exception {
        minioClient.downloadObject(DownloadObjectArgs.builder()
                .bucket(minioProperties.bucket()).object(key).filename(target.toString()).build());
    }

    private void upload(String key, Path file, String contentType) throws Exception {
        minioClient.uploadObject(UploadObjectArgs.builder()
                .bucket(minioProperties.bucket()).object(key).filename(file.toString())
                .contentType(contentType).build());
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not delete {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean up {}", dir, e);
        }
    }
}
