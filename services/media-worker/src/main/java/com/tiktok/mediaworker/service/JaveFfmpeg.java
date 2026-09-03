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
     * Both invocations copy or decode a single frame, so they are bounded by disk rather than by
     * the length of the video. A run past this is ffmpeg stuck on a malformed file, not a long
     * one, and the listener thread it is holding matters more than the output would.
     */
    private static final long TIMEOUT_SECONDS = 120;

    /** Only the tail of ffmpeg's diagnostics goes in the log; the head is banner and stream dumps. */
    private static final int LOG_TAIL_CHARS = 1000;

    private final String executable = new DefaultFFMPEGLocator().getExecutablePath();

    @Override
    public boolean faststart(Path source, Path target) {
        return run("faststart remux", List.of(
                "-i", source.toString(),
                "-c", "copy",
                "-movflags", "+faststart",
                "-f", "mp4",
                target.toString()));
    }

    @Override
    public boolean stillFrame(Path source, Path target, int atSecond) {
        return run("still frame", List.of(
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

    private boolean run(String what, List<String> arguments) {
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

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("ffmpeg {} did not finish within {}s, killing it", what, TIMEOUT_SECONDS);
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
