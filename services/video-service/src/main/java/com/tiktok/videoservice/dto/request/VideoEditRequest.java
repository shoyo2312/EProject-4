package com.tiktok.videoservice.dto.request;

import com.tiktok.event.video.VideoFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The edits made in the app's editor, applied by media-worker before the usual transcode. The
 * app only previews them; this is what makes the stored video match the preview.
 *
 * <p>Every bound here is a bound on ffmpeg work the client can ask for: each segment is one more
 * trim and concat input, and a speed far from 1 is a chain of audio filters. Times are not checked
 * against the video's length — video-service never sees the file — so media-worker clamps them
 * to what the upload actually holds.
 */
public record VideoEditRequest(
        // Optional: absent or empty is the whole upload at normal speed, for an edit that only
        // changes the colour.
        @Size(max = MAX_SEGMENTS) List<@Valid @NotNull Segment> segments,
        @Valid Filter filter,
        @Size(max = MAX_OVERLAYS) List<@Valid @NotNull Overlay> overlays
) {

    public static final int MAX_SEGMENTS = 20;

    /**
     * Each layer is a PNG media-worker downloads, decodes and composites over every frame, so this
     * bounds that work. The app merges items that share their timing into one layer, so ten is
     * already ten different moments.
     */
    public static final int MAX_OVERLAYS = 10;

    /** Trim and speed only. */
    public VideoEditRequest(List<Segment> segments) {
        this(segments, null, null);
    }

    /** Without text or stickers. */
    public VideoEditRequest(List<Segment> segments, Filter filter) {
        this(segments, filter, null);
    }

    /** An edit that is neither a cut, a colour nor an overlay is a client bug, not a no-op to store. */
    @AssertTrue(message = "an edit needs segments, a filter or overlays")
    public boolean isNotEmpty() {
        return (segments != null && !segments.isEmpty()) || filter != null
                || (overlays != null && !overlays.isEmpty());
    }

    /** Every overlay's words, in layer order — what caption moderation reads. */
    public List<String> overlayTexts() {
        return overlays == null ? List.of() : overlays.stream()
                .filter(overlay -> overlay.texts() != null)
                .flatMap(overlay -> overlay.texts().stream())
                .toList();
    }

    /**
     * One layer of the app's text and stickers, drawn into a transparent PNG the shape of the video.
     *
     * @param imageUrl the {@code fileUrl} of an {@code image/png} upload from POST /upload-url.
     *                 Whose upload it is, and that it exists, is checked at publish
     * @param start    seconds into the edited video when the layer appears; absent from the start
     * @param end      when it goes; absent until the end
     * @param texts    the words drawn in the layer, as typed. Screened like the caption: the
     *                 picture itself reaches no text check, so this is what one reads
     */
    public record Overlay(
            @NotBlank @Size(max = 1024) String imageUrl,
            @DecimalMin("0") @DecimalMax("36000") Double start,
            @DecimalMin("0") @DecimalMax("36000") Double end,
            @Size(max = MAX_OVERLAY_TEXTS) List<@NotNull @Size(max = 150) String> texts
    ) {

        public static final int MAX_OVERLAY_TEXTS = 20;

        /** Shown throughout, no words. */
        public Overlay(String imageUrl) {
            this(imageUrl, null, null, null);
        }

        @AssertTrue(message = "end must be after start")
        public boolean isOrdered() {
            return start == null || end == null || end > start;
        }
    }

    /**
     * @param id        one of {@link VideoFilter}'s ids; checked here so an unknown one is a 400
     *                  rather than a video published without the colour its owner picked
     * @param intensity 0 to 1; absent is the full filter
     */
    public record Filter(
            @NotBlank @Size(max = 32) String id,
            @DecimalMin("0") @DecimalMax("1") Double intensity
    ) {

        @AssertTrue(message = "unknown filter")
        public boolean isKnown() {
            return id == null || VideoFilter.byId(id).isPresent();
        }

        public double intensityOrFull() {
            return intensity == null ? 1.0 : intensity;
        }
    }

    /**
     * @param start seconds into the raw upload where this part begins
     * @param end   seconds into the raw upload where this part ends
     * @param speed playback rate, the editor's 0.3x to 3x with headroom
     */
    public record Segment(
            @NotNull @DecimalMin("0") @DecimalMax("36000") Double start,
            @NotNull @DecimalMin("0") @DecimalMax("36000") Double end,
            @NotNull @DecimalMin("0.25") @DecimalMax("4") Double speed
    ) {

        /** A segment that ends where it starts is no video at all. */
        @AssertTrue(message = "end must be after start")
        public boolean isOrdered() {
            return start == null || end == null || end > start;
        }
    }
}
