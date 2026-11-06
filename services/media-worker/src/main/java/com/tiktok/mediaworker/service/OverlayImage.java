package com.tiktok.mediaworker.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Checks the editor's overlay before ffmpeg decodes it. The file is a client upload, and a PNG's
 * header names its size before a single pixel is decompressed: a few KB of zeros can claim
 * 50000x50000 and cost ffmpeg ten gigabytes to inflate. So the header is read here, by hand, and
 * anything that is not a PNG of a sane size never reaches the decoder.
 */
public final class OverlayImage {

    /** The app draws at 720 or 1280 wide; this is twice that with room to spare. */
    public static final int MAX_SIDE = 4096;

    /** The largest overlay video-service lets anyone upload, checked again before the download. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] IHDR = {'I', 'H', 'D', 'R'};

    /** Signature, chunk length, chunk type, then width and height. */
    private static final int HEADER_BYTES = 24;

    private OverlayImage() {
    }

    /** As the PNG header states it. */
    public record Size(int width, int height) {
    }

    /**
     * @return the size the header states, which the filter graph needs to scale it
     * @throws MediaRejectedException if {@code file} is not a PNG, or claims a size outside
     *                                1..{@link #MAX_SIDE} on either side
     */
    public static Size requireUsable(Path file) throws IOException {
        byte[] header;
        try (InputStream in = Files.newInputStream(file)) {
            header = in.readNBytes(HEADER_BYTES);
        }
        if (header.length < HEADER_BYTES
                || !Arrays.equals(header, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)
                || !Arrays.equals(header, 12, 16, IHDR, 0, IHDR.length)) {
            throw new MediaRejectedException("The text and sticker overlay is not a PNG.");
        }
        ByteBuffer size = ByteBuffer.wrap(header, 16, 8);
        // Unsigned 32-bit on the wire; read as int, anything past 2^31 is negative and refused.
        int width = size.getInt();
        int height = size.getInt();
        if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
            throw new MediaRejectedException("The text and sticker overlay is %dx%d; the maximum is %dx%d."
                    .formatted(Integer.toUnsignedLong(width), Integer.toUnsignedLong(height), MAX_SIDE, MAX_SIDE));
        }
        return new Size(width, height);
    }
}
