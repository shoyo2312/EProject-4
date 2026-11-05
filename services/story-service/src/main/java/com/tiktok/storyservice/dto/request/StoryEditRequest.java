package com.tiktok.storyservice.dto.request;

import com.tiktok.event.video.VideoFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The edits made in the app's editor, the same recipe as a video's {@code edit} (video doc 3.2):
 * media-worker renders them before the story is screened, and viewers are served what it rendered.
 *
 * <p>On an image the segments are ignored — there is nothing to cut — and an image with music
 * comes back a clip {@link #photoSeconds} long, since a song needs a timeline to play on.
 *
 * <p>Every bound is a bound on ffmpeg work the client can ask for, as on video-service's.
 */
public record StoryEditRequest(
        @Size(max = MAX_SEGMENTS) List<@Valid @NotNull Segment> segments,
        @Valid Filter filter,
        @Size(max = MAX_OVERLAYS) List<@Valid @NotNull Overlay> overlays,
        @Valid Music music,
        @DecimalMin("1") @DecimalMax("15") Double photoSeconds
) {

    public static final int MAX_SEGMENTS = 20;

    /** Each layer is one more PNG composited over every frame. */
    public static final int MAX_OVERLAYS = 10;

    /** How long an image with music plays when the app does not say. */
    public static final double DEFAULT_PHOTO_SECONDS = 6;

    /** An edit that changes nothing is a client bug, not a no-op to render. */
    @AssertTrue(message = "an edit needs segments, a filter, overlays or music")
    public boolean isNotEmpty() {
        return (segments != null && !segments.isEmpty()) || filter != null
                || (overlays != null && !overlays.isEmpty()) || music != null;
    }

    public double photoSecondsOrDefault() {
        return photoSeconds == null ? DEFAULT_PHOTO_SECONDS : photoSeconds;
    }

    /** Every overlay's words, in layer order — what the overlay moderation reads. */
    public List<String> overlayTexts() {
        return overlays == null ? List.of() : overlays.stream()
                .filter(overlay -> overlay.texts() != null)
                .flatMap(overlay -> overlay.texts().stream())
                .toList();
    }

    /**
     * A track from {@code GET /videos/music}, mixed under the story.
     *
     * @param trackId        the catalogue's id; looked up again at create
     * @param start          seconds into the track the story's first frame plays against
     * @param volume         0 to 1, the track's level; absent is full
     * @param originalVolume 0 to 1, the clip's own sound; absent is full, 0 replaces it
     */
    public record Music(
            @NotBlank @Pattern(regexp = "\\d{1,12}") String trackId,
            @DecimalMin("0") @DecimalMax("3600") Double start,
            @DecimalMin("0") @DecimalMax("1") Double volume,
            @DecimalMin("0") @DecimalMax("1") Double originalVolume
    ) {

        public double startOrZero() {
            return start == null ? 0 : start;
        }

        public double volumeOrFull() {
            return volume == null ? 1 : volume;
        }

        public double originalVolumeOrFull() {
            return originalVolume == null ? 1 : originalVolume;
        }
    }

    /**
     * One layer of the app's text and stickers, a transparent PNG the shape of the picture.
     *
     * @param imageUrl the {@code fileUrl} of an {@code image/png} upload from POST /stories/upload-url
     * @param start    seconds into the rendered story when the layer appears; absent from the start
     * @param end      when it goes; absent until the end
     * @param texts    the words drawn in the layer, as typed — screened like the caption
     */
    public record Overlay(
            @NotBlank @Size(max = 1024) String imageUrl,
            @DecimalMin("0") @DecimalMax("3600") Double start,
            @DecimalMin("0") @DecimalMax("3600") Double end,
            @Size(max = 20) List<@NotNull @Size(max = 150) String> texts
    ) {

        @AssertTrue(message = "end must be after start")
        public boolean isOrdered() {
            return start == null || end == null || end > start;
        }
    }

    /**
     * @param id        one of {@link VideoFilter}'s ids
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
     * @param start seconds into the upload where this part begins
     * @param end   seconds into the upload where this part ends
     * @param speed playback rate
     */
    public record Segment(
            @NotNull @DecimalMin("0") @DecimalMax("3600") Double start,
            @NotNull @DecimalMin("0") @DecimalMax("3600") Double end,
            @NotNull @DecimalMin("0.25") @DecimalMax("4") Double speed
    ) {

        @AssertTrue(message = "end must be after start")
        public boolean isOrdered() {
            return start == null || end == null || end > start;
        }
    }
}
