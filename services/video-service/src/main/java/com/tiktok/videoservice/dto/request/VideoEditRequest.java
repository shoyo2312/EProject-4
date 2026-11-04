package com.tiktok.videoservice.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
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
        @NotEmpty @Size(max = MAX_SEGMENTS) List<@Valid @NotNull Segment> segments
) {

    public static final int MAX_SEGMENTS = 20;

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
