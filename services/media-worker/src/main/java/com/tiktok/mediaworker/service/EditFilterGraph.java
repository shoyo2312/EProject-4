package com.tiktok.mediaworker.service;

import com.tiktok.event.video.VideoFilter;
import com.tiktok.event.video.VideoPublishedEvent.Edit;
import com.tiktok.event.video.VideoPublishedEvent.Edit.Segment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns the app editor's recipe into one ffmpeg {@code -filter_complex} graph: each segment is
 * trimmed out of the upload and retimed to its speed, then the segments are joined back to back.
 *
 * <p>Only numbers ever reach the graph, and only ones this class formatted itself. The recipe
 * comes from a client, and a filter graph is a small language of its own, so a string taken from
 * the recipe and pasted in would be a way to run any filter on any file this worker can open.
 *
 * <p>video-service validated the recipe without ever seeing the file, so a segment may run past
 * the end of the upload. An end past it needs nothing — trim stops where the stream does — but a
 * segment that starts past it is dropped rather than kept as an empty input, which concat would
 * refuse. The length compared against is the probe's, rounded to the second, which is why no end
 * is ever cut to it: a 10.4s clip probes as 10, and clamping to that would lose its last frames.
 */
public final class EditFilterGraph {

    /** The video and audio pads the graph ends in, for {@code -map}. */
    public static final String VIDEO_OUT = "[vout]";
    public static final String AUDIO_OUT = "[aout]";

    /**
     * One layer of the editor's text and stickers: a PNG input after the upload, in order — the
     * first layer is input 1.
     *
     * @param size  as {@link OverlayImage#requireUsable} read it from the header
     * @param start seconds on the edited video's clock when it appears, null from the start
     * @param end   when it goes, null at the end
     */
    public record Layer(OverlayImage.Size size, Double start, Double end) {

        /** Shown throughout. */
        public Layer(OverlayImage.Size size) {
            this(size, null, null);
        }
    }

    /** atempo takes 0.5 to 2 in one step on older builds; anything further is chained. */
    private static final double ATEMPO_MIN = 0.5;
    private static final double ATEMPO_MAX = 2.0;

    /** Shorter than one frame at 30fps is a segment nobody could see, and trim may yield nothing. */
    private static final double MIN_SEGMENT_SECONDS = 0.05;

    /** The same cap as {@link JaveFfmpeg#normalize}: long side 1280, short side 720, never upscaled. */
    private static final String SCALE = "scale='min(iw,if(gt(iw,ih),1280,720))':-2";

    private EditFilterGraph() {
    }

    /**
     * Whether applying {@code edit} to an upload of {@code durationSeconds} would change anything.
     * A recipe of one full-length segment at normal speed is what an editor sends when the user
     * opened it and touched nothing, and re-encoding for that is minutes of CPU for no difference.
     */
    public static boolean changesAnything(Edit edit, double durationSeconds) {
        if (colour(edit).isPresent() || (edit != null && !edit.overlays().isEmpty())) {
            return true;
        }
        List<Segment> segments = usable(edit, durationSeconds);
        if (segments.isEmpty()) {
            return edit != null && !edit.segments().isEmpty();
        }
        if (segments.size() > 1) {
            return true;
        }
        Segment only = segments.getFirst();
        return only.start() > MIN_SEGMENT_SECONDS
                || only.end() < durationSeconds - MIN_SEGMENT_SECONDS
                || only.speed() != 1.0;
    }

    /**
     * @return the segments left once clamped to the upload, in the recipe's order. Empty means the
     *         recipe asks for nothing the upload holds, which the caller should refuse rather than
     *         publish the unedited upload in its place
     */
    public static List<Segment> usable(Edit edit, double durationSeconds) {
        if (edit == null) {
            return List.of();
        }
        // No segments is a recipe that only changes the colour: the whole upload, as recorded. A
        // second past the probed length, since the probe rounds and trim stops at the end anyway.
        if (edit.segments().isEmpty()) {
            return durationSeconds > 0 ? List.of(new Segment(0, durationSeconds + 1, 1.0)) : List.of();
        }
        List<Segment> usable = new ArrayList<>();
        for (Segment segment : edit.segments()) {
            double start = Math.max(0, segment.start());
            if (start < durationSeconds
                    && segment.end() - start >= MIN_SEGMENT_SECONDS
                    && segment.speed() > 0) {
                usable.add(new Segment(start, segment.end(), segment.speed()));
            }
        }
        return usable;
    }

    /**
     * The colour matrix the recipe's filter stands for, or empty for none. An id this build does
     * not know — the app and video-service can be newer than this worker — is no filter rather
     * than a failed video: unlike a cut, a missing colour shows nothing the uploader meant to hide.
     *
     * @return nine entries, row-major, from {@link VideoFilter#matrix}
     */
    public static Optional<double[]> colour(Edit edit) {
        if (edit == null || edit.filter() == null || edit.filter().intensity() <= 0) {
            return Optional.empty();
        }
        return VideoFilter.byId(edit.filter().id()).map(filter -> filter.matrix(edit.filter().intensity()));
    }

    /** Without a colour filter. */
    public static String build(List<Segment> segments, boolean withAudio) {
        return build(segments, null, List.of(), withAudio);
    }

    /** Without overlays. */
    public static String build(List<Segment> segments, double[] colour, boolean withAudio) {
        return build(segments, colour, List.of(), withAudio);
    }

    /**
     * @param segments  from {@link #usable}, never empty
     * @param colour    from {@link #colour}, or null to leave the colour alone. Applied after the
     *                  join and before the size cap, in the same pass as everything else
     * @param overlays  the editor's text and sticker layers, inputs 1, 2… in order, the first
     *                  lowest. Laid over the picture last, after the size cap, each stretched to
     *                  exactly the picture's size: the app drew them in the video's own shape, so
     *                  this only changes their resolution — and drawing them after the cap keeps
     *                  the text sharp rather than scaled twice. The colour filter is not applied
     *                  to them, as the app's preview does not. Each shows only between its start
     *                  and end, read on the clock of the edited video — after the concat, where
     *                  {@code t} is what the viewer's player will show
     * @param withAudio whether the upload has an audio stream; a graph naming {@code [0:a]} on a
     *                  silent file is an error, not silence
     */
    public static String build(List<Segment> segments, double[] colour, List<Layer> overlays,
                               boolean withAudio) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("An edit needs at least one segment");
        }
        StringBuilder graph = new StringBuilder();
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            graph.append("[0:v]trim=start=").append(number(segment.start()))
                    .append(":end=").append(number(segment.end()))
                    .append(",setpts=(PTS-STARTPTS)*").append(number(1 / segment.speed()))
                    .append("[v").append(i).append("];");
            joined.append("[v").append(i).append(']');
            if (withAudio) {
                graph.append("[0:a]atrim=start=").append(number(segment.start()))
                        .append(":end=").append(number(segment.end()))
                        .append(",asetpts=PTS-STARTPTS");
                for (String step : atempo(segment.speed())) {
                    graph.append(",atempo=").append(step);
                }
                graph.append("[a").append(i).append("];");
                joined.append("[a").append(i).append(']');
            }
        }
        graph.append(joined)
                .append("concat=n=").append(segments.size())
                .append(":v=1:a=").append(withAudio ? 1 : 0)
                .append("[vcat]").append(withAudio ? AUDIO_OUT : "").append(';')
                .append("[vcat]");
        if (colour != null) {
            graph.append(colourChannelMixer(colour)).append(',');
        }
        graph.append(SCALE);
        String below = "[vscaled]";
        for (int i = 0; i < overlays.size(); i++) {
            Layer layer = overlays.get(i);
            // A single still: overlay keeps showing an input's last frame once it has ended, so
            // the one frame is there for as long as enable lets it be.
            graph.append(below).append(';')
                    .append('[').append(i + 1).append(":v]").append(below)
                    .append(scaleToReference(layer.size())).append("[ov").append(i).append("][vb").append(i).append("];")
                    .append("[vb").append(i).append("][ov").append(i).append("]overlay=x=0:y=0")
                    .append(enable(layer));
            below = "[vo" + i + "]";
        }
        graph.append(VIDEO_OUT);
        return graph.toString();
    }

    /** ffmpeg's timeline option, on the overlay's clock; nothing for a layer shown throughout. */
    private static String enable(Layer layer) {
        if (layer.start() != null && layer.end() != null) {
            return ":enable='between(t," + number(layer.start()) + "," + number(layer.end()) + ")'";
        }
        if (layer.start() != null) {
            return ":enable='gte(t," + number(layer.start()) + ")'";
        }
        if (layer.end() != null) {
            return ":enable='lte(t," + number(layer.end()) + ")'";
        }
        return "";
    }

    /**
     * scale2ref sizing the overlay to the picture. Which variables name the picture depends on the
     * build: the ffmpeg 4.4 bundled with JAVE has them backwards — {@code iw}/{@code ih} are the
     * reference and {@code main_w}/{@code main_h} the image being scaled — where the documented
     * and later behaviour is the reverse. Rather than bet on one, the expression asks which pair
     * is the overlay, whose size the worker read from its header, and takes the other. When the
     * two are the same size either answer is right.
     */
    private static String scaleToReference(OverlayImage.Size overlay) {
        String isOverlay = "eq(iw,%d)*eq(ih,%d)".formatted(overlay.width(), overlay.height());
        return "scale2ref=w='if(%s,main_w,iw)':h='if(%s,main_h,ih)'".formatted(isOverlay, isOverlay);
    }

    /**
     * ffmpeg's colorchannelmixer takes the same row-major matrix as the app's ColorFilter: rr, rg
     * and rb are how much of the input's red, green and blue go into the output's red.
     */
    private static String colourChannelMixer(double[] m) {
        if (m.length != 9) {
            throw new IllegalArgumentException("A colour matrix has nine entries, not " + m.length);
        }
        String[] names = {"rr", "rg", "rb", "gr", "gg", "gb", "br", "bg", "bb"};
        StringBuilder mixer = new StringBuilder("colorchannelmixer=");
        for (int i = 0; i < 9; i++) {
            mixer.append(i == 0 ? "" : ":").append(names[i]).append('=').append(number(m[i]));
        }
        return mixer.toString();
    }

    /**
     * atempo changes speed without changing pitch, but only within a factor of two per filter on
     * the builds this worker may run, so 3x is 2x then 1.5x and 0.3x is 0.5x then 0.6x.
     */
    static List<String> atempo(double speed) {
        List<String> steps = new ArrayList<>();
        double remaining = speed;
        while (remaining > ATEMPO_MAX) {
            steps.add(number(ATEMPO_MAX));
            remaining /= ATEMPO_MAX;
        }
        while (remaining < ATEMPO_MIN) {
            steps.add(number(ATEMPO_MIN));
            remaining /= ATEMPO_MIN;
        }
        if (Math.abs(remaining - 1.0) > 1e-9) {
            steps.add(number(remaining));
        }
        return steps;
    }

    /** ffmpeg parses its own arguments, so the decimal separator must not follow the locale. */
    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
