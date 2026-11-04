package com.tiktok.mediaworker.service;

import com.tiktok.event.video.VideoPublishedEvent.Edit;
import com.tiktok.event.video.VideoPublishedEvent.Edit.Segment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EditFilterGraphTest {

    private static Edit edit(Segment... segments) {
        return new Edit(1, List.of(segments));
    }

    @Test
    void build_trimsRetimesAndJoinsEverySegmentInOrder() {
        String graph = EditFilterGraph.build(List.of(new Segment(1, 3, 1), new Segment(5, 9, 2)), true);

        assertThat(graph).isEqualTo(
                "[0:v]trim=start=1.000000:end=3.000000,setpts=(PTS-STARTPTS)*1.000000[v0];"
                        + "[0:a]atrim=start=1.000000:end=3.000000,asetpts=PTS-STARTPTS[a0];"
                        + "[0:v]trim=start=5.000000:end=9.000000,setpts=(PTS-STARTPTS)*0.500000[v1];"
                        + "[0:a]atrim=start=5.000000:end=9.000000,asetpts=PTS-STARTPTS,atempo=2.000000[a1];"
                        + "[v0][a0][v1][a1]concat=n=2:v=1:a=1[vcat][aout];"
                        + "[vcat]scale='min(iw,if(gt(iw,ih),1280,720))':-2[vout]");
    }

    /** A graph naming [0:a] on a file with no audio is an error, not silence. */
    @Test
    void build_silentUpload_namesNoAudioStream() {
        String graph = EditFilterGraph.build(List.of(new Segment(0, 2, 1)), false);

        assertThat(graph)
                .doesNotContain("[0:a]")
                .doesNotContain("[aout]")
                .contains("concat=n=1:v=1:a=0[vcat];");
    }

    @Test
    void build_refusesAnEmptyEdit() {
        assertThatThrownBy(() -> EditFilterGraph.build(List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void atempo_chainsSpeedsBeyondWhatOneFilterTakes() {
        assertThat(EditFilterGraph.atempo(1)).isEmpty();
        assertThat(EditFilterGraph.atempo(1.5)).containsExactly("1.500000");
        assertThat(EditFilterGraph.atempo(3)).containsExactly("2.000000", "1.500000");
        assertThat(EditFilterGraph.atempo(0.3)).containsExactly("0.500000", "0.600000");
    }

    /** The decimal separator is ffmpeg's, not the worker's locale's. */
    @Test
    void build_writesNumbersTheSameWayInEveryLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(EditFilterGraph.build(List.of(new Segment(0.5, 1.5, 1)), false))
                    .contains("start=0.500000");
        } finally {
            Locale.setDefault(before);
        }
    }

    /**
     * An end past the upload is kept as sent: the probe rounds to the second, and trim stops where
     * the stream does anyway.
     */
    @Test
    void usable_dropsSegmentsThatStartPastTheEndOrHoldNothing() {
        List<Segment> usable = EditFilterGraph.usable(edit(
                new Segment(-2, 3, 1),
                new Segment(4, 4.01, 1),
                new Segment(12, 15, 1),
                new Segment(8, 20, 1)), 10);

        assertThat(usable).containsExactly(new Segment(0, 3, 1), new Segment(8, 20, 1));
    }

    @Test
    void changesAnything_onlyForARecipeThatDiffersFromTheUpload() {
        assertThat(EditFilterGraph.changesAnything(null, 10)).isFalse();
        assertThat(EditFilterGraph.changesAnything(edit(new Segment(0, 10, 1)), 10)).isFalse();
        assertThat(EditFilterGraph.changesAnything(edit(new Segment(0, 7, 1)), 10)).isTrue();
        assertThat(EditFilterGraph.changesAnything(edit(new Segment(2, 10, 1)), 10)).isTrue();
        assertThat(EditFilterGraph.changesAnything(edit(new Segment(0, 10, 2)), 10)).isTrue();
        assertThat(EditFilterGraph.changesAnything(
                edit(new Segment(0, 5, 1), new Segment(5, 10, 1)), 10)).isTrue();
        // Nothing usable is still a change: the caller refuses it rather than publish the upload.
        assertThat(EditFilterGraph.changesAnything(edit(new Segment(20, 30, 1)), 10)).isTrue();
    }
}
