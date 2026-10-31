package com.tiktok.chatservice.service;

import com.tiktok.chatservice.exception.InvalidImageException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * What the server itself decides about an uploaded picture, from its bytes alone: the declared
 * content type and file name are never consulted. The type comes from the file's signature, and the
 * picture must really decode — read at a coarse subsampling, so a small file declaring a huge canvas
 * cannot make the server allocate it.
 */
final class ImageInspector {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int MAX_SIDE = 8000;
    static final long MAX_PIXELS = 40_000_000L;

    record Inspected(String mime, int width, int height) {
    }

    private ImageInspector() {
    }

    static Inspected inspect(byte[] bytes) {
        if (bytes.length == 0) {
            throw new InvalidImageException("IMAGE_EMPTY", "Tệp ảnh trống");
        }
        if (bytes.length > MAX_BYTES) {
            throw new InvalidImageException("IMAGE_TOO_LARGE", "Ảnh tối đa 5 MB");
        }
        String mime = sniff(bytes);
        if (mime == null) {
            throw new InvalidImageException("IMAGE_UNSUPPORTED_TYPE", "Chỉ hỗ trợ ảnh JPEG, PNG, GIF hoặc WebP");
        }
        if ("image/webp".equals(mime)) {
            // ImageIO has no WebP reader: the container is checked and the canvas size read from its header.
            int[] size = webpSize(bytes);
            if (size == null) {
                throw undecodable();
            }
            checkSize(size[0], size[1]);
            return new Inspected(mime, size[0], size[1]);
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw undecodable();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                checkSize(width, height);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = (int) Math.max(1, Math.ceil(Math.sqrt((double) width * height / 1_000_000d)));
                param.setSourceSubsampling(step, step, 0, 0);
                reader.read(0, param);
                boolean swap = "image/jpeg".equals(mime) && exifSwapsAxes(bytes);
                return swap ? new Inspected(mime, height, width) : new Inspected(mime, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidImageException invalid) {
                throw invalid;
            }
            throw undecodable();
        }
    }

    private static void checkSize(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE
                || (long) width * height > MAX_PIXELS) {
            throw new InvalidImageException("IMAGE_DIMENSIONS", "Kích thước ảnh tối đa " + MAX_SIDE + "px mỗi cạnh");
        }
    }

    /**
     * Width and height from a WebP's first chunk (lossy VP8, lossless VP8L or extended VP8X), or null when the
     * RIFF container is not consistent: wrong size, a chunk that runs past the end, an unknown first chunk.
     */
    static int[] webpSize(byte[] b) {
        if (b.length < 21) {
            return null;
        }
        long riff = le(b, 4, 4);
        if (riff + 8 > b.length || riff < 12) {
            return null;
        }
        long chunk = le(b, 16, 4);
        if (20 + chunk > b.length) {
            return null;
        }
        String kind = new String(b, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return switch (kind) {
            case "VP8 " -> chunk >= 10 && (b[23] & 0xFF) == 0x9D && (b[24] & 0xFF) == 0x01 && (b[25] & 0xFF) == 0x2A
                    ? new int[]{(int) (le(b, 26, 2) & 0x3FFF), (int) (le(b, 28, 2) & 0x3FFF)} : null;
            case "VP8L" -> {
                if (chunk < 5 || (b[20] & 0xFF) != 0x2F) {
                    yield null;
                }
                long bits = le(b, 21, 4);
                yield new int[]{(int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1};
            }
            case "VP8X" -> chunk >= 10 ? new int[]{(int) le(b, 24, 3) + 1, (int) le(b, 27, 3) + 1} : null;
            default -> null;
        };
    }

    private static long le(byte[] b, int at, int bytes) {
        long value = 0;
        for (int i = bytes - 1; i >= 0; i--) {
            value = (value << 8) | (b[at + i] & 0xFF);
        }
        return value;
    }

    private static InvalidImageException undecodable() {
        return new InvalidImageException("IMAGE_CORRUPT", "Không đọc được ảnh này");
    }

    private static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return "image/gif";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /**
     * Browsers draw a JPEG upright using its EXIF orientation, which ImageIO ignores; orientations 5–8
     * turn the picture a quarter, so the display width is the stored height.
     */
    static boolean exifSwapsAxes(byte[] b) {
        int pos = 2;
        while (pos + 4 <= b.length && (b[pos] & 0xFF) == 0xFF) {
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return false;
            }
            int len = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
            if (marker == 0xE1 && len >= 16 && pos + 2 + len <= b.length
                    && b[pos + 4] == 'E' && b[pos + 5] == 'x' && b[pos + 6] == 'i' && b[pos + 7] == 'f') {
                return orientation(b, pos + 10, pos + 2 + len) >= 5;
            }
            pos += 2 + len;
        }
        return false;
    }

    private static int orientation(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return 1;
        }
        boolean little = b[tiff] == 'I';
        int ifd = tiff + (int) read(b, tiff + 4, 4, little);
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int entries = (int) read(b, ifd, 2, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (read(b, entry, 2, little) == 0x0112) {
                return (int) read(b, entry + 8, 2, little);
            }
        }
        return 1;
    }

    private static long read(byte[] b, int at, int bytes, boolean little) {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int v = b[at + (little ? bytes - 1 - i : i)] & 0xFF;
            value = (value << 8) | v;
        }
        return value;
    }
}
