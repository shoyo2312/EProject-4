package com.tiktok.event.video;

import java.util.Arrays;
import java.util.Optional;

/**
 * The colour filters the app's editor offers, by the id that travels in
 * {@link VideoPublishedEvent.Edit.Filter}. video-service accepts only these ids; media-worker
 * applies the matrix with ffmpeg's {@code colorchannelmixer}.
 *
 * <p>Each filter is a 3x3 matrix over gamma-encoded RGB, row-major: output red is the first row
 * applied to the input's red, green and blue, and so on. That is exactly what Flutter's
 * {@code ColorFilter.matrix} does with the same numbers in its first three columns, which is how
 * the app's preview and the published video come out the same colour. The app keeps a copy of
 * this table ({@code video_filters.dart}); a change here is a change there.
 */
public enum VideoFilter {

    WARM("warm", diagonal(1.08, 1.0, 0.86)),
    COOL("cool", diagonal(0.9, 1.0, 1.12)),
    VIVID("vivid", saturation(1.4)),
    MUTED("muted", saturation(0.6)),
    MONO("mono", saturation(0)),
    VINTAGE("vintage", new double[]{
            0.393, 0.769, 0.189,
            0.349, 0.686, 0.168,
            0.272, 0.534, 0.131});

    private static final double[] IDENTITY = diagonal(1, 1, 1);

    private final String id;
    private final double[] matrix;

    VideoFilter(String id, double[] matrix) {
        this.id = id;
        this.matrix = matrix;
    }

    public String id() {
        return id;
    }

    public static Optional<VideoFilter> byId(String id) {
        return Arrays.stream(values()).filter(f -> f.id.equals(id)).findFirst();
    }

    /**
     * The matrix at {@code intensity}: 0 is no change, 1 the full filter, and anything between a
     * straight blend of the two — the same blend the app's intensity slider previews.
     *
     * @return nine entries, row-major; a copy, so callers cannot change the filter
     */
    public double[] matrix(double intensity) {
        double k = Math.max(0, Math.min(1, intensity));
        double[] blended = new double[9];
        for (int i = 0; i < 9; i++) {
            blended[i] = IDENTITY[i] + (matrix[i] - IDENTITY[i]) * k;
        }
        return blended;
    }

    private static double[] diagonal(double r, double g, double b) {
        return new double[]{r, 0, 0, 0, g, 0, 0, 0, b};
    }

    /**
     * Saturation by mixing each channel with the pixel's luma (Rec. 601 weights): 0 is grey, 1 the
     * original, above 1 more saturated.
     */
    private static double[] saturation(double s) {
        double[] luma = {0.299, 0.587, 0.114};
        double[] m = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                m[row * 3 + col] = (1 - s) * luma[col] + (row == col ? s : 0);
            }
        }
        return m;
    }
}
