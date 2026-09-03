package com.tiktok.mediaworker.service;

import java.nio.file.Path;

/**
 * The two ffmpeg invocations the pipeline makes, split behind an interface for the same reason
 * {@link VideoProbe} is: TranscodeServiceImpl's orchestration is worth unit-testing without
 * spawning a process, and the process wrapper is worth testing against a real clip.
 *
 * <p>Both return {@code false} rather than throwing when ffmpeg refuses the input. Neither is a
 * condition for having a watchable video — a remux that the mp4 muxer will not take still leaves
 * the original file, and a still frame that will not decode still leaves the client's own poster.
 * Turning either into an exception would report a playable upload as FAILED, which is terminal.
 */
public interface Ffmpeg {

    /**
     * Rewrites {@code source} into {@code target} as an mp4 with the moov atom in front, so a
     * player can start on the first bytes instead of waiting for the whole file. Streams are
     * copied, not re-encoded, so this is I/O bound and lossless.
     *
     * @return false if the source's streams cannot be copied into an mp4 container
     */
    boolean faststart(Path source, Path target);

    /**
     * Writes one decoded frame from {@code atSecond} into {@code target} as a JPEG, scaled to a
     * height of 720 with the aspect ratio kept and the container's rotation applied.
     *
     * @return false if no frame could be decoded at that point
     */
    boolean stillFrame(Path source, Path target, int atSecond);
}
