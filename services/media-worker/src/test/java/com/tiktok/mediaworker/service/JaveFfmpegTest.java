package com.tiktok.mediaworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real ffmpeg binary JAVE2 unpacks against the same 3-second fixture the probe
 * test uses. The orchestration around it is unit-tested against a mocked Ffmpeg elsewhere.
 */
class JaveFfmpegTest {

    private final Ffmpeg ffmpeg = new JaveFfmpeg();

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
        assertThat(indexOfAtom(target, "moov")).isLessThan(indexOfAtom(target, "mdat"));
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

    /** Byte offset of a top-level mp4 box name, which is enough to compare two boxes' order. */
    private static int indexOfAtom(Path file, String atom) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
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
}
