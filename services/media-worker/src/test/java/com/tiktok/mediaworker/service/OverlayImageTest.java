package com.tiktok.mediaworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OverlayImageTest {

    @Test
    void aPngOfAFrameSize_isUsable(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("overlay.png");
        ImageIO.write(new BufferedImage(720, 1280, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());

        assertThatCode(() -> OverlayImage.requireUsable(png)).doesNotThrowAnyException();
    }

    @Test
    void somethingElse_isRejected(@TempDir Path dir) throws Exception {
        Path jpeg = dir.resolve("overlay.png");
        ImageIO.write(new BufferedImage(72, 128, BufferedImage.TYPE_INT_RGB), "jpg", jpeg.toFile());

        assertThatThrownBy(() -> OverlayImage.requireUsable(jpeg)).isInstanceOf(MediaRejectedException.class);
    }

    @Test
    void aFileShorterThanAPngHeader_isRejected(@TempDir Path dir) throws Exception {
        Path tiny = Files.write(dir.resolve("overlay.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        assertThatThrownBy(() -> OverlayImage.requireUsable(tiny)).isInstanceOf(MediaRejectedException.class);
    }

    /**
     * The header alone decides: a few bytes claiming a huge canvas are refused before anything
     * would inflate them — including a width past 2^31, which reads as negative.
     */
    @Test
    void aHeaderClaimingAHugeCanvas_isRejected(@TempDir Path dir) throws Exception {
        for (int[] size : new int[][]{{50_000, 100}, {100, 4097}, {0, 100}, {0x80000000, 100}}) {
            Path bomb = Files.write(dir.resolve("overlay.png"), header(size[0], size[1]));

            assertThatThrownBy(() -> OverlayImage.requireUsable(bomb))
                    .as("%dx%d", Integer.toUnsignedLong(size[0]), size[1])
                    .isInstanceOf(MediaRejectedException.class);
        }
    }

    private static byte[] header(int width, int height) {
        return ByteBuffer.allocate(33)
                .put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'})
                .putInt(13).put(new byte[]{'I', 'H', 'D', 'R'})
                .putInt(width).putInt(height)
                .put(new byte[]{8, 6, 0, 0, 0})
                .putInt(0)
                .array();
    }
}
