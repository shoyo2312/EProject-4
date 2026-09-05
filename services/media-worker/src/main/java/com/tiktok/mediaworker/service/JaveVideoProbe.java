package com.tiktok.mediaworker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.info.AudioInfo;
import ws.schild.jave.info.MultimediaInfo;
import ws.schild.jave.info.VideoInfo;
import ws.schild.jave.info.VideoSize;

import java.net.URI;

@Slf4j
@Component
public class JaveVideoProbe implements VideoProbe {

    @Override
    public ProbedVideo probe(String url) {
        try {
            MultimediaInfo info = new MultimediaObject(URI.create(url).toURL()).getInfo();

            long millis = info.getDuration();
            if (millis <= 0) {
                throw new IllegalStateException("No readable duration for " + safe(url));
            }
            VideoInfo video = info.getVideo();
            VideoSize size = video == null ? null : video.getSize();
            if (size == null) {
                throw new IllegalStateException("No readable video stream in " + safe(url));
            }
            AudioInfo audio = info.getAudio();

            return new ProbedVideo(
                    (int) Math.round(millis / 1000.0),
                    codec(video.getDecoder()),
                    audio == null ? null : codec(audio.getDecoder()),
                    size.getWidth(),
                    size.getHeight());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // ws.schild throws a checked EncoderException plus MalformedURLException; to a
            // caller they mean the same thing — the file could not be probed.
            throw new IllegalStateException("Could not probe " + safe(url), e);
        }
    }

    /**
     * ffmpeg names a decoder with its profile and tag attached — {@code "h264 (High) (avc1 /
     * 0x31637661)"} — and only the first word is the codec the format decision turns on.
     */
    private static String codec(String decoder) {
        if (decoder == null || decoder.isBlank()) {
            return null;
        }
        int space = decoder.indexOf(' ');
        return space < 0 ? decoder : decoder.substring(0, space);
    }

    /** Presigned URLs carry a signature query string; keep it out of logs and messages. */
    private static String safe(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?…";
    }
}
