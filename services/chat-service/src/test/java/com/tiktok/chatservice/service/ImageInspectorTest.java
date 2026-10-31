package com.tiktok.chatservice.service;

import com.tiktok.chatservice.exception.InvalidImageException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageInspectorTest {

    private static byte[] encode(String format, int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String codeOf(byte[] bytes) {
        return ((InvalidImageException) org.junit.jupiter.api.Assertions.assertThrows(InvalidImageException.class,
                () -> ImageInspector.inspect(bytes))).getCode();
    }

    @Test
    void acceptsJpegPngAndGifAndReportsTheirSizeAndTheTypeItFound() {
        assertThat(ImageInspector.inspect(encode("png", 40, 30))).isEqualTo(new ImageInspector.Inspected("image/png", 40, 30));
        assertThat(ImageInspector.inspect(encode("jpg", 40, 30))).isEqualTo(new ImageInspector.Inspected("image/jpeg", 40, 30));
        assertThat(ImageInspector.inspect(encode("gif", 40, 30))).isEqualTo(new ImageInspector.Inspected("image/gif", 40, 30));
    }

    @Test
    void refusesWhatIsNotAPictureWhateverItIsCalled() {
        assertThat(codeOf("<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("IMAGE_UNSUPPORTED_TYPE");
        assertThat(codeOf("%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8))).isEqualTo("IMAGE_UNSUPPORTED_TYPE");
        assertThat(codeOf("<html>hi</html>".getBytes(StandardCharsets.UTF_8))).isEqualTo("IMAGE_UNSUPPORTED_TYPE");
        // A BMP is a real picture, but not one this service stores.
        assertThat(codeOf(encode("bmp", 10, 10))).isEqualTo("IMAGE_UNSUPPORTED_TYPE");
        assertThat(codeOf("RIFF....WAVEfmt ".getBytes(StandardCharsets.UTF_8))).isEqualTo("IMAGE_UNSUPPORTED_TYPE");
    }

    /** A RIFF/WEBP container with one chunk: {@code kind} is VP8, VP8L or VP8X, {@code payload} its data. */
    private static byte[] webp(String kind, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int chunk = payload.length;
        int riff = 4 + 8 + chunk + (chunk % 2);
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{(byte) riff, (byte) (riff >> 8), (byte) (riff >> 16), (byte) (riff >> 24)});
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(kind.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{(byte) chunk, (byte) (chunk >> 8), (byte) (chunk >> 16), (byte) (chunk >> 24)});
        out.writeBytes(payload);
        if (chunk % 2 == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    @Test
    void readsTheSizeOfLossyLosslessAndExtendedWebp() {
        byte[] lossy = new byte[12];
        lossy[3] = (byte) 0x9D;
        lossy[4] = 0x01;
        lossy[5] = 0x2A;
        lossy[6] = 40;
        lossy[8] = 30;
        assertThat(ImageInspector.inspect(webp("VP8 ", lossy))).isEqualTo(new ImageInspector.Inspected("image/webp", 40, 30));

        int bits = 9 | (19 << 14);
        byte[] lossless = {0x2F, (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24)};
        assertThat(ImageInspector.inspect(webp("VP8L", lossless))).isEqualTo(new ImageInspector.Inspected("image/webp", 10, 20));

        byte[] extended = new byte[10];
        extended[4] = 99;
        extended[7] = 49;
        assertThat(ImageInspector.inspect(webp("VP8X", extended))).isEqualTo(new ImageInspector.Inspected("image/webp", 100, 50));
    }

    @Test
    void refusesAWebpThatIsOnlyAHeaderOrTooBig() {
        assertThat(codeOf("RIFF....WEBPVP8 ".getBytes(StandardCharsets.UTF_8))).isEqualTo("IMAGE_CORRUPT");
        byte[] lossy = webp("VP8 ", new byte[12]);
        assertThat(codeOf(lossy)).isEqualTo("IMAGE_CORRUPT");
        byte[] truncated = Arrays.copyOf(webp("VP8L", new byte[]{0x2F, 0, 0, 0, 0}), 22);
        assertThat(codeOf(truncated)).isEqualTo("IMAGE_CORRUPT");
        byte[] huge = new byte[10];
        huge[4] = (byte) 0xFF;
        huge[5] = (byte) 0x3F;
        assertThat(codeOf(webp("VP8X", huge))).isEqualTo("IMAGE_DIMENSIONS");
    }

    @Test
    void refusesAnEmptyFile() {
        assertThat(codeOf(new byte[0])).isEqualTo("IMAGE_EMPTY");
    }

    @Test
    void refusesAFileOverTheLimitBeforeLookingInside() {
        byte[] big = new byte[(int) ImageInspector.MAX_BYTES + 1];
        big[0] = (byte) 0x89;
        assertThat(codeOf(big)).isEqualTo("IMAGE_TOO_LARGE");
        assertThat(ImageInspector.inspect(encode("png", 100, 100)).width()).isEqualTo(100);
    }

    @Test
    void refusesAFileThatOnlyStartsLikeAPicture() {
        byte[] png = encode("png", 50, 50);
        assertThat(codeOf(Arrays.copyOf(png, 20))).isEqualTo("IMAGE_CORRUPT");
        byte[] fake = "\u0089PNG\r\n\u001a\nnot really a png at all".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(codeOf(fake)).isEqualTo("IMAGE_CORRUPT");
        byte[] jpeg = encode("jpg", 50, 50);
        assertThat(codeOf(Arrays.copyOf(jpeg, 30))).isEqualTo("IMAGE_CORRUPT");
    }

    @Test
    void refusesACanvasOverTheSideLimit() {
        assertThat(codeOf(encode("png", ImageInspector.MAX_SIDE + 1, 1))).isEqualTo("IMAGE_DIMENSIONS");
    }

    @Test
    void anExifRotationOfAQuarterTurnSwapsTheDisplaySize() throws IOException {
        byte[] jpeg = encode("jpg", 40, 30);
        assertThat(ImageInspector.inspect(withOrientation(jpeg, 6))).isEqualTo(new ImageInspector.Inspected("image/jpeg", 30, 40));
        assertThat(ImageInspector.inspect(withOrientation(jpeg, 8))).isEqualTo(new ImageInspector.Inspected("image/jpeg", 30, 40));
        assertThat(ImageInspector.inspect(withOrientation(jpeg, 3))).isEqualTo(new ImageInspector.Inspected("image/jpeg", 40, 30));
        assertThat(ImageInspector.inspect(withOrientation(jpeg, 1))).isEqualTo(new ImageInspector.Inspected("image/jpeg", 40, 30));
    }

    /** SOI, then a little-endian APP1/Exif segment holding only the orientation tag, then the rest of the file. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] tiff = {
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
                0x01, 0x00,
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, (byte) orientation, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
        };
        byte[] header = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + header.length + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.writeBytes(header);
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    @Test
    void aTruncatedExifSegmentDoesNotBreakTheUpload() {
        byte[] jpeg = encode("jpg", 40, 30);
        assertThatThrownBy(() -> ImageInspector.inspect(Arrays.copyOf(jpeg, 8))).isInstanceOf(InvalidImageException.class);
    }
}
