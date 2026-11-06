package com.tiktok.event.video;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VideoFilterTest {

    private static final double[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    @Test
    void ids_areWhatTheAppSends() {
        assertThat(VideoFilter.byId("warm")).contains(VideoFilter.WARM);
        assertThat(VideoFilter.byId("vintage")).contains(VideoFilter.VINTAGE);
        assertThat(VideoFilter.byId("WARM")).isEmpty();
        assertThat(VideoFilter.byId("sepia")).isEmpty();
    }

    @Test
    void intensityZero_changesNothing() {
        for (VideoFilter filter : VideoFilter.values()) {
            assertThat(filter.matrix(0)).as(filter.id()).containsExactly(IDENTITY, within(1e-12));
        }
    }

    @Test
    void intensity_blendsHalfwayAtOneHalf() {
        assertThat(VideoFilter.WARM.matrix(0.5))
                .containsExactly(new double[]{1.04, 0, 0, 0, 1, 0, 0, 0, 0.93}, within(1e-12));
    }

    /** Grey keeps its brightness: every row of a saturation matrix sums to one. */
    @Test
    void saturationFilters_leaveGreyAlone() {
        for (VideoFilter filter : new VideoFilter[]{VideoFilter.VIVID, VideoFilter.MUTED, VideoFilter.MONO}) {
            double[] m = filter.matrix(1);
            for (int row = 0; row < 3; row++) {
                assertThat(m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]).as(filter.id()).isCloseTo(1, within(1e-9));
            }
        }
    }

    @Test
    void mono_givesEveryChannelTheSameLuma() {
        double[] m = VideoFilter.MONO.matrix(1);
        assertThat(new double[]{m[0], m[1], m[2]}).containsExactly(new double[]{0.299, 0.587, 0.114}, within(1e-12));
        assertThat(new double[]{m[3], m[4], m[5]}).containsExactly(new double[]{0.299, 0.587, 0.114}, within(1e-12));
    }

    @Test
    void matrix_isACopy() {
        VideoFilter.WARM.matrix(1)[0] = 99;
        assertThat(VideoFilter.WARM.matrix(1)[0]).isEqualTo(1.08);
    }
}
