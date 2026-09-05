package com.tiktok.mediaworker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ws.schild.jave.process.ffmpeg.DefaultFFMPEGLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the ffmpeg binary JAVE2 already unpacks for probing. Using that one rather than a binary
 * on PATH keeps the worker's only native dependency the one the build already ships for every
 * platform — there is no Dockerfile step to keep in step with this class.
 */
@Slf4j
@Component
public class JaveFfmpeg implements Ffmpeg {

    /**
     * A remux copies streams and a still frame decodes one picture, so both are bounded by disk
     * rather than by the length of the video. A run past this is ffmpeg stuck on a malformed
     * file, not a long one, and the listener thread it is holding matters more than the output.
     */
    private static final long COPY_TIMEOUT_SECONDS = 120;

    /**
     * Normalizing decodes and re-encodes every frame, so it does scale with the video's length.
     * Ten minutes covers the longest upload the limits allow with room to spare; past that the
     * file is beyond what one worker should hold a partition for. The consumer's poll interval is
     * set against this number — see application.yml.
     */
    private static final long ENCODE_TIMEOUT_SECONDS = 600;

    /** Long enough to show what the video is, short enough that nobody waits for it to loop. */
    private static final int PREVIEW_SECONDS = 3;

    /** Only the tail of ffmpeg's diagnostics goes in the log; the head is banner and stream dumps. */
    private static final int LOG_TAIL_CHARS = 1000;

    private final String executable = new DefaultFFMPEGLocator().getExecutablePath();

    @Override
    public boolean faststart(Path source, Path target) {
        return run("faststart remux", COPY_TIMEOUT_SECONDS, List.of(
                "-i", source.toString(),
                "-c", "copy",
                "-movflags", "+faststart",
                "-f", "mp4",
                target.toString()));
    }

    @Override
    public boolean normalize(Path source, Path target) {
        return run("normalize", ENCODE_TIMEOUT_SECONDS, List.of(
                "-i", source.toString(),
                // iw/ih here are the dimensions after ffmpeg has applied the container's rotation,
                // so the cap lands on the video as it will be seen: the long side at 1280, the
                // short one at 720. min() against iw is what keeps a smaller upload from being
                // upscaled into a bigger file for nothing, and -2 rounds the other side to an even
                // number, which yuv420p requires.
                "-vf", "scale='min(iw,if(gt(iw,ih),1280,720))':-2",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                // yuv420p and the high profile are what Safari and older Android decoders accept;
                // libx264 would otherwise carry a 10-bit or 4:2:2 source's pixel format through.
                "-profile:v", "high",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-b:a", "128k",
                "-ac", "2",
                "-movflags", "+faststart",
                "-f", "mp4",
                target.toString()));
    }

    @Override
    public boolean stillFrame(Path source, Path target, int atSecond) {
        return run("still frame", COPY_TIMEOUT_SECONDS, List.of(
                // -ss ahead of -i seeks by index instead of decoding up to the mark.
                "-ss", String.valueOf(atSecond),
                "-i", source.toString(),
                "-frames:v", "1",
                // -2 keeps the aspect ratio and lands on an even width, which the JPEG encoder needs.
                "-vf", "scale=-2:720",
                "-q:v", "3",
                "-f", "image2",
                target.toString()));
    }

    @Override
    public boolean animatedPreview(Path source, Path target, int fromSecond) {
        return run("animated preview", COPY_TIMEOUT_SECONDS, List.of(
                "-ss", String.valueOf(fromSecond),
                "-t", String.valueOf(PREVIEW_SECONDS),
                "-i", source.toString(),
                // 8 fps and 240 tall is the whole budget: enough motion to read what the video is,
                // small enough that a feed can pull one per card without a loading state.
                "-vf", "fps=8,scale=-2:240",
                "-an",
                "-c:v", "libwebp_anim",
                "-loop", "0",
                "-q:v", "60",
                "-f", "webp",
                target.toString()));
    }

    private boolean run(String what, long timeoutSeconds, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(executable, "-nostdin", "-y", "-loglevel", "error"));
        command.addAll(arguments);

        Path diagnostics = null;
        Process process = null;
        try {
            // Diagnostics go to a file rather than a pipe: a pipe nobody drains fills its buffer
            // and ffmpeg blocks on the write forever, which no timeout on waitFor would catch.
            diagnostics = Files.createTempFile("ffmpeg-", ".log");
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(diagnostics.toFile())
                    .start();
            process.getOutputStream().close();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("ffmpeg {} did not finish within {}s, killing it", what, timeoutSeconds);
                return false;
            }
            if (process.exitValue() != 0) {
                log.warn("ffmpeg {} exited {}: {}", what, process.exitValue(), tail(diagnostics));
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted during ffmpeg {}", what);
            return false;
        } catch (IOException e) {
            log.warn("Could not run ffmpeg {}", what, e);
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            delete(diagnostics);
        }
    }

    private static String tail(Path diagnostics) {
        try {
            String text = Files.readString(diagnostics).strip();
            return text.length() <= LOG_TAIL_CHARS ? text : text.substring(text.length() - LOG_TAIL_CHARS);
        } catch (IOException e) {
            return "(no diagnostics)";
        }
    }

    private static void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}", path, e);
        }
    }
}
