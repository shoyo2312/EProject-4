package com.tiktok.mediaworker.service;

import com.tiktok.event.video.VideoFilter;
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

    @Test
    void build_putsTheColourAfterTheJoinAndBeforeTheSizeCap() {
        String graph = EditFilterGraph.build(List.of(new Segment(0, 2, 1)),
                new double[]{1.08, 0, 0, 0, 1, 0, 0, 0, 0.86}, false);

        assertThat(graph).endsWith("[vcat]colorchannelmixer="
                + "rr=1.080000:rg=0.000000:rb=0.000000:"
                + "gr=0.000000:gg=1.000000:gb=0.000000:"
                + "br=0.000000:bg=0.000000:bb=0.860000,"
                + "scale='min(iw,if(gt(iw,ih),1280,720))':-2[vout]");
    }

    /**
     * Last, at the capped size: stretched to exactly the picture, since the app drew it in the
     * video's shape, and after the colour so a mono video keeps its coloured text — as the app
     * shows it.
     */
    @Test
    void build_laysTheOverlayOverThePictureAfterTheColourAndTheSizeCap() {
        String graph = EditFilterGraph.build(List.of(new Segment(0, 2, 1)), VideoFilter.MONO.matrix(1),
                List.of(new EditFilterGraph.Layer(new OverlayImage.Size(720, 1280))), false);

        assertThat(graph).endsWith("scale='min(iw,if(gt(iw,ih),1280,720))':-2[vscaled];"
                + "[1:v][vscaled]scale2ref="
                + "w='if(eq(iw,720)*eq(ih,1280),main_w,iw)':h='if(eq(iw,720)*eq(ih,1280),main_h,ih)'"
                + "[ov0][vb0];"
                + "[vb0][ov0]overlay=x=0:y=0[vout]");
        assertThat(graph.indexOf("colorchannelmixer")).isLessThan(graph.indexOf("[1:v]"));
    }

    /** Layers stack in order, each shown only over its own stretch of the edited video's clock. */
    @Test
    void build_stacksLayersInOrderEachOnItsOwnTimeline() {
        OverlayImage.Size size = new OverlayImage.Size(72, 128);
        String graph = EditFilterGraph.build(List.of(new Segment(0, 9, 1)), null, List.of(
                new EditFilterGraph.Layer(size),
                new EditFilterGraph.Layer(size, 1.5, 4.0),
                new EditFilterGraph.Layer(size, 2.0, null),
                new EditFilterGraph.Layer(size, null, 3.0)), false);

        assertThat(graph)
                .contains("[1:v][vscaled]scale2ref=")
                .contains("[vb0][ov0]overlay=x=0:y=0[vo0];[2:v][vo0]scale2ref=")
                .contains("[vb1][ov1]overlay=x=0:y=0:enable='between(t,1.500000,4.000000)'[vo1];")
                .contains("[vb2][ov2]overlay=x=0:y=0:enable='gte(t,2.000000)'[vo2];")
                .endsWith("[vb3][ov3]overlay=x=0:y=0:enable='lte(t,3.000000)'[vout]");
    }

    /**
     * Mixed on the edited clock, after the join: the video's own sound at its level, the track
     * from its start offset at its level, as long as the video's sound lasts, neither normalised.
     */
    @Test
    void build_mixesTheMusicUnderTheVideosOwnSound() {
        String graph = EditFilterGraph.build(List.of(new Segment(0, 5, 1)), null, List.of(),
                new EditFilterGraph.Mix(1, 12.5, 0.8, 0.3, 5), true);

        assertThat(graph)
                .contains("concat=n=1:v=1:a=1[vcat][acat];")
                .endsWith("[vout];[acat]volume=0.300000[aorig];"
                        + "[1:a]atrim=start=12.500000,asetpts=PTS-STARTPTS,volume=0.800000[amus];"
                        + "[aorig][amus]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]");
    }

    /** A silent video has no sound to end the mix with, so the track is cut at the video's length. */
    @Test
    void build_putsTheMusicAloneUnderASilentVideo() {
        String graph = EditFilterGraph.build(List.of(new Segment(0, 5, 1)), null, List.of(),
                new EditFilterGraph.Mix(2, 0, 1, 1, 4.5), false);

        assertThat(graph)
                .doesNotContain("[0:a]")
                .endsWith("[vout];[2:a]atrim=start=0.000000:duration=4.500000,asetpts=PTS-STARTPTS,volume=1.000000[aout]");
        assertThat(EditFilterGraph.hasAudio(false, new EditFilterGraph.Mix(2, 0, 1, 1, 4.5))).isTrue();
        assertThat(EditFilterGraph.hasAudio(false, null)).isFalse();
    }

    @Test
    void changesAnything_musicAloneIsAChange() {
        Edit musicOnly = new Edit(1, List.of(), null, List.of(), new Edit.Music("https://x.jamendo.com/t", 0, 1, 1));

        assertThat(EditFilterGraph.changesAnything(musicOnly, 10)).isTrue();
    }

    @Test
    void changesAnything_anOverlayAloneIsAChange() {
        Edit textOnly = new Edit(1, List.of(), null, List.of(new Edit.Overlay("s3://video-media/raw/1/t.png")));

        assertThat(EditFilterGraph.changesAnything(textOnly, 10)).isTrue();
        assertThat(EditFilterGraph.usable(textOnly, 10)).containsExactly(new Segment(0, 11, 1));
    }

    @Test
    void colour_isTheCataloguedMatrixAtTheRequestedIntensity() {
        assertThat(EditFilterGraph.colour(new Edit(1, List.of(), new Edit.Filter("warm", 0.5))))
                .hasValueSatisfying(m -> assertThat(m).containsExactly(VideoFilter.WARM.matrix(0.5)));
    }

    @Test
    void colour_noneForNoFilterZeroIntensityOrAnUnknownId() {
        assertThat(EditFilterGraph.colour(null)).isEmpty();
        assertThat(EditFilterGraph.colour(edit(new Segment(0, 2, 1)))).isEmpty();
        assertThat(EditFilterGraph.colour(new Edit(1, List.of(), new Edit.Filter("warm", 0)))).isEmpty();
        assertThat(EditFilterGraph.colour(new Edit(1, List.of(), new Edit.Filter("nope", 1)))).isEmpty();
    }

    /** No segments is the whole upload at normal speed: a recipe that only changes the colour. */
    @Test
    void usable_noSegmentsIsTheWholeUpload() {
        assertThat(EditFilterGraph.usable(new Edit(1, List.of(), new Edit.Filter("mono", 1)), 10))
                .containsExactly(new Segment(0, 11, 1));
    }

    @Test
    void changesAnything_aColourAloneIsAChange() {
        assertThat(EditFilterGraph.changesAnything(new Edit(1, List.of(), new Edit.Filter("mono", 1)), 10)).isTrue();
        assertThat(EditFilterGraph.changesAnything(new Edit(1, List.of(new Segment(0, 10, 1)),
                new Edit.Filter("mono", 1)), 10)).isTrue();
        assertThat(EditFilterGraph.changesAnything(new Edit(1, List.of(), new Edit.Filter("nope", 1)), 10)).isFalse();
    }
}
