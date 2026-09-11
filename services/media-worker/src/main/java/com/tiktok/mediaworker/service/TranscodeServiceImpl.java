package com.tiktok.mediaworker.service;

import com.tiktok.mediaworker.config.MediaVideoProperties;
import com.tiktok.mediaworker.config.MinioProperties;
import io.minio.DownloadObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Turns an upload into the objects the client needs: a playback file a player can start on the
 * first bytes of, and the artwork a feed shows before anyone presses play.
 *
 * <p>Which of two paths an upload takes is decided by {@link ProbedVideo#needsNormalizing()}.
 * A file already in the shape browsers play — H.264, AAC or silent, 720p or below — is only
 * remuxed with its moov atom moved to the front ({@code -movflags +faststart}): same streams,
 * same bytes of video, only the index relocated, and the CPU cost flat in the video's length.
 * Anything else is decoded and re-encoded to that shape, which is the one genuinely expensive
 * thing this worker does and the reason the consumer's poll interval is set where it is.
 *
 * <p>The artwork is a still thumbnail plus a few seconds of animated WebP for the hover preview,
 * both taken one second in.
 *
 * <p>HLS segmenting and multi-bitrate variants are deliberately not here. The playback artifact
 * is one flat mp4.
 *
 * <p>ponytail: the file is pulled through this worker's disk, where the original copy-only
 * version never moved a byte out of MinIO. Moving the moov atom means rewriting the container, so
 * there is no server-side operation that does it. Budget roughly 2x the upload's size in scratch
 * space per concurrent transcode.
 *
 * <p>The two paths fail differently, and on purpose. A remux that the mp4 muxer refuses leaves
 * the upload stored as it arrived, because that branch only runs on files already known to be
 * playable. A normalize that fails is thrown, because falling back there would store an HEVC or
 * AV1 file at the playback key and hand the viewer a video that silently will not play — worse
 * than the FAILED the uploader would at least see. A frame that will not decode leaves
 * {@code thumbnailUrl} null, which is the state the client already falls back on, and a preview
 * that cannot be built leaves {@code previewUrl} null and the hover showing the still instead.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranscodeServiceImpl implements TranscodeService {

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final MediaVideoProperties videoLimits;
    private final VideoProbe videoProbe;
    private final Ffmpeg ffmpeg;

    @Override
    @SneakyThrows
    public TranscodeResult transcode(String videoId, String rawFileUrl) {
        String bucket = minioProperties.bucket();
        String sourceKey = MediaKeys.objectKey(rawFileUrl, bucket).orElseThrow(() -> new IllegalArgumentException(
                "Raw upload %s of video %s is not in bucket %s".formatted(rawFileUrl, videoId, bucket)));

        // Checked before the download so an oversized upload costs one HEAD, not its own size in
        // traffic and scratch space.
        long sizeBytes = minioClient.statObject(StatObjectArgs.builder()
                .bucket(bucket).object(sourceKey).build()).size();
        if (sizeBytes > videoLimits.maxBytes()) {
            throw new MediaRejectedException("Video is %s; the maximum is %s."
                    .formatted(humanBytes(sizeBytes), humanBytes(videoLimits.maxBytes())));
        }

        Path work = Files.createTempDirectory("transcode-" + videoId + "-");
        try {
            Path source = work.resolve("source");
            minioClient.downloadObject(DownloadObjectArgs.builder()
                    .bucket(bucket).object(sourceKey).filename(source.toString()).build());

            ProbedVideo probed = videoProbe.probe(source.toUri().toString());
            int durationSeconds = probed.durationSeconds();
            if (durationSeconds > videoLimits.maxDurationSeconds()) {
                throw new MediaRejectedException("Video is %s; the maximum is %s."
                        .formatted(humanDuration(durationSeconds), humanDuration(videoLimits.maxDurationSeconds())));
            }

            String playbackKey = MediaKeys.playback(videoId);
            Path playback = work.resolve("playback.mp4");
            if (probed.needsNormalizing()) {
                log.info("Normalizing video {} from {}/{} at {}x{}",
                        videoId, probed.videoCodec(), probed.audioCodec(), probed.width(), probed.height());
                if (!ffmpeg.normalize(source, playback)) {
                    // Thrown rather than fallen back on: the consumer retries this a few times and
                    // then reports FAILED, which is the honest outcome for a file that could not be
                    // turned into something a browser plays.
                    throw new IllegalStateException("Could not normalize video " + videoId);
                }
            } else if (!ffmpeg.faststart(source, playback)) {
                log.info("Video {} could not be remuxed to mp4, storing the upload unchanged", videoId);
                playback = source;
            }
            String hlsUrl = upload(bucket, playbackKey, playback, "video/mp4");

            int stillSecond = thumbnailSecond(durationSeconds);

            String thumbnailUrl = null;
            Path thumbnail = work.resolve("thumbnail.jpg");
            boolean hasStillFrame = ffmpeg.stillFrame(source, thumbnail, stillSecond);
            if (hasStillFrame) {
                thumbnailUrl = upload(bucket, MediaKeys.thumbnail(videoId), thumbnail, "image/jpeg");
            } else {
                log.info("Video {} yielded no still frame, leaving it without a thumbnail", videoId);
            }

            Dimension display = displaySize(probed, hasStillFrame ? thumbnail : null);

            String previewUrl = null;
            Path preview = work.resolve("preview.webp");
            if (ffmpeg.animatedPreview(source, preview, stillSecond)) {
                previewUrl = upload(bucket, MediaKeys.preview(videoId), preview, "image/webp");
            } else {
                log.info("Video {} yielded no animated preview, hover falls back to the thumbnail", videoId);
            }

            log.info("Video {} is playable at {} ({}s, {}x{})",
                    videoId, playbackKey, durationSeconds, display.width, display.height);
            return new TranscodeResult(thumbnailUrl, previewUrl, hlsUrl, durationSeconds,
                    display.width, display.height);
        } finally {
            deleteRecursively(work);
        }
    }

    /**
     * One second in, so the still and the preview both start past the fade-in and black leader
     * most clips open on, but never past the halfway mark of a clip too short to have one.
     */
    private static int thumbnailSecond(int durationSeconds) {
        return Math.min(1, durationSeconds / 2);
    }

    /**
     * The size a player will show, which is not always the size ffmpeg probed. A rotated capture
     * is stored in its pre-rotation dimensions — a portrait phone video reads as 1920x1080 — and
     * a client handed those lays every landscape video out as portrait, or the reverse.
     *
     * <p>The still frame settles it: {@link Ffmpeg#stillFrame} decodes with the container's
     * rotation applied, so its orientation is the one a viewer sees. Only the orientation is
     * taken from it — the frame is scaled to a fixed height, so its pixel counts are not the
     * video's. Without a still (a file that would not decode one) the probe's numbers stand,
     * which is right for everything unrotated and better than sending nothing at all.
     */
    static Dimension displaySize(ProbedVideo probed, Path stillFrame) {
        int width = probed.width();
        int height = probed.height();

        boolean rotated = jpegSize(stillFrame)
                // Equal sides are a square, which says nothing about rotation either way.
                .filter(still -> still.width != still.height && width != height)
                .map(still -> (still.width > still.height) != (width > height))
                .orElse(false);

        return rotated ? new Dimension(height, width) : new Dimension(width, height);
    }

    /** Header read only — the reader answers with the size without decoding any pixels. */
    private static Optional<Dimension> jpegSize(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = stream == null ? null : ImageIO.getImageReaders(stream);
            if (readers == null || !readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return Optional.of(new Dimension(reader.getWidth(0), reader.getHeight(0)));
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            log.warn("Could not measure {}, falling back to the probed dimensions", file, e);
            return Optional.empty();
        }
    }

    @SneakyThrows
    private String upload(String bucket, String key, Path file, String contentType) {
        minioClient.uploadObject(UploadObjectArgs.builder()
                .bucket(bucket).object(key)
                .filename(file.toString())
                .contentType(contentType)
                .build());
        return url(bucket, key);
    }

    private String url(String bucket, String key) {
        return "%s/%s/%s".formatted(minioProperties.endpoint(), bucket, key);
    }

    /**
     * Scratch space is the one resource a failed transcode can leak, and the listener retries
     * three times before giving up — so this runs on the way out of every path, successful or not.
     */
    private static void deleteRecursively(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}", directory, e);
        }
    }

    private static String humanBytes(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 1024 ? "%.2f GB".formatted(mb / 1024) : "%.0f MB".formatted(mb);
    }

    private static String humanDuration(int seconds) {
        return "%dm%02ds".formatted(seconds / 60, seconds % 60);
    }
}
