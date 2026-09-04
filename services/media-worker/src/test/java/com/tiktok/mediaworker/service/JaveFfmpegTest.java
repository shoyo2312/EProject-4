package com.tiktok.mediaworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real ffmpeg binary JAVE2 unpacks against the same 3-second fixture the probe
 * test uses. The orchestration around it is unit-tested against a mocked Ffmpeg elsewhere.
 */
class JaveFfmpegTest {

    private final Ffmpeg ffmpeg = new JaveFfmpeg();
    private final VideoProbe probe = new JaveVideoProbe();

    private static Path fixture() throws Exception {
        return Path.of(JaveFfmpegTest.class.getResource("/fixtures/sample-3s.mp4").toURI());
    }

    @Test
    void faststart_rewritesTheClipWithItsIndexInFront(@TempDir Path work) throws Exception {
        Path target = work.resolve("playback.mp4");

        assertThat(ffmpeg.faststart(fixture(), target)).isTrue();
        assertThat(Files.size(target)).isPositive();
        // The moov atom is what a player needs before it can start; faststart's whole job is
        // putting it ahead of the media data rather than after it.
        byte[] mp4 = Files.readAllBytes(target);
        assertThat(indexOfAtom(mp4, "moov")).isLessThan(indexOfAtom(mp4, "mdat"));
    }

    @Test
    void faststart_refusesInputItCannotCopyIntoMp4(@TempDir Path work) throws Exception {
        Path notMedia = work.resolve("notes.txt");
        Files.writeString(notMedia, "this is not a video");

        assertThat(ffmpeg.faststart(notMedia, work.resolve("playback.mp4"))).isFalse();
    }

    @Test
    void stillFrame_writesAJpegFromTheRequestedSecond(@TempDir Path work) throws Exception {
        Path target = work.resolve("thumbnail.jpg");

        assertThat(ffmpeg.stillFrame(fixture(), target, 1)).isTrue();

        byte[] image = Files.readAllBytes(target);
        assertThat(image).hasSizeGreaterThan(1000);
        // JPEG start-of-image marker; a text file at the right key was the bug this replaces.
        assertThat(image[0] & 0xFF).isEqualTo(0xFF);
        assertThat(image[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void stillFrame_refusesInputWithNoDecodableFrame(@TempDir Path work) throws Exception {
        Path notMedia = work.resolve("notes.txt");
        Files.writeString(notMedia, "this is not a video");

        assertThat(ffmpeg.stillFrame(notMedia, work.resolve("thumbnail.jpg"), 1)).isFalse();
    }

    /** Byte offset of an mp4 box name, which is enough to compare two boxes' order. */
    private static int indexOfAtom(byte[] bytes, String atom) {
        byte[] needle = atom.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return Integer.MAX_VALUE;
    }

    @Test
    void normalize_bakesRotationIntoThePixelsAndCapsTheLongSideAt1280(@TempDir Path work) throws Exception {
        // A 1920x1080 capture tagged to display rotated is what a phone held upright produces:
        // stored landscape, meant to be seen as 1080x1920 portrait.
        Path rotated = rotate90(synthesize(work.resolve("rotated.mp4"), "1920x1080"));
        Path target = work.resolve("normalized.mp4");

        assertThat(ffmpeg.normalize(rotated, target)).isTrue();

        ProbedVideo probed = probe.probe(target.toUri().toString());
        // 720x1280, not 1280x720: the rotation is in the pixels now, and the short side is capped.
        assertThat(probed.width()).isEqualTo(720);
        assertThat(probed.height()).isEqualTo(1280);
        assertThat(probed.videoCodec()).isEqualTo("h264");
        assertThat(probed.audioCodec()).isEqualTo("aac");
        assertThat(probed.needsNormalizing()).isFalse();
    }

    @Test
    void normalize_leavesNoRotationBehindToBeAppliedTwice(@TempDir Path work) throws Exception {
        Path rotated = rotate90(synthesize(work.resolve("rotated.mp4"), "1920x1080"));
        Path once = work.resolve("once.mp4");
        Path twice = work.resolve("twice.mp4");

        assertThat(ffmpeg.normalize(rotated, once)).isTrue();
        assertThat(ffmpeg.normalize(once, twice)).isTrue();

        // If the first pass had baked the rotation in AND kept the display matrix, the second
        // would rotate the already-upright picture back to 1280x720.
        ProbedVideo probed = probe.probe(twice.toUri().toString());
        assertThat(probed.width()).isEqualTo(720);
        assertThat(probed.height()).isEqualTo(1280);
    }

    @Test
    void normalize_capsAnUnrotatedLandscapeCaptureAt1280x720(@TempDir Path work) throws Exception {
        Path source = synthesize(work.resolve("uhd.mp4"), "3840x2160");
        Path target = work.resolve("normalized.mp4");

        assertThat(ffmpeg.normalize(source, target)).isTrue();

        ProbedVideo probed = probe.probe(target.toUri().toString());
        assertThat(probed.width()).isEqualTo(1280);
        assertThat(probed.height()).isEqualTo(720);
    }

    @Test
    void normalize_doesNotUpscaleSomethingSmallerThan720p(@TempDir Path work) throws Exception {
        // The source is VP9, so it is normalized for its codec rather than its size — and the
        // scale cap must not turn a 640x360 clip into a bigger file for nothing.
        Path source = synthesize(work.resolve("small.webm"), "640x360");
        Path target = work.resolve("normalized.mp4");

        assertThat(ffmpeg.normalize(source, target)).isTrue();

        ProbedVideo probed = probe.probe(target.toUri().toString());
        assertThat(probed.width()).isEqualTo(640);
        assertThat(probed.height()).isEqualTo(360);
    }

    @Test
    void normalize_refusesInputItCannotDecode(@TempDir Path work) throws Exception {
        Path notMedia = work.resolve("notes.txt");
        Files.writeString(notMedia, "this is not a video");

        assertThat(ffmpeg.normalize(notMedia, work.resolve("normalized.mp4"))).isFalse();
    }

    /**
     * Builds a two-second clip of the requested size with a tone on it, straight from the same
     * binary under test. Cheaper and more honest than checking in a fixture per shape. A
     * {@code .webm} target is written as VP9/Opus, which is what a browser recorder produces.
     */
    private static Path synthesize(Path target, String size) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                new DefaultFFMPEGLocator().getExecutablePath(), "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc=size=" + size + ":rate=15:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2"));
        command.addAll(target.toString().endsWith(".webm")
                ? List.of("-c:v", "libvpx-vp9", "-b:v", "200k", "-c:a", "libopus")
                : List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac"));
        command.add(target.toString());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).inheritIO().start();
        assertThat(process.waitFor()).as("building the %s fixture", size).isZero();
        return target;
    }

    /**
     * Marks the clip as needing a quarter turn to display, the way a phone held upright tags its
     * landscape-stored capture. This is written straight into the track header's display matrix
     * rather than through a command-line option: the {@code -metadata rotate=} form is a no-op in
     * the ffmpeg version bundled here, and the matrix is what a real capture actually carries.
     */
    private static Path rotate90(Path mp4) throws IOException {
        byte[] bytes = Files.readAllBytes(mp4);
        int tkhd = indexOfAtom(bytes, "tkhd");
        assertThat(tkhd).as("track header in %s", mp4).isNotEqualTo(Integer.MAX_VALUE);

        // The matrix sits 48 bytes into the tkhd box, which starts with its 4-byte length ahead
        // of the name. Nine fixed-point entries, the 90-degree rotation of the identity.
        ByteBuffer matrix = ByteBuffer.wrap(bytes, tkhd - 4 + 48, 36);
        for (int entry : new int[]{0, 0x00010000, 0, 0xFFFF0000, 0, 0, 0, 0, 0x40000000}) {
            matrix.putInt(entry);
        }
        Files.write(mp4, bytes);
        return mp4;
    }
}
