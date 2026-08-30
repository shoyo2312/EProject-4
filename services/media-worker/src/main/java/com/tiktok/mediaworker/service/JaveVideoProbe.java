package com.tiktok.mediaworker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.info.MultimediaInfo;

import java.net.URI;

@Slf4j
@Component
public class JaveVideoProbe implements VideoProbe {

    @Override
    public int durationSeconds(String httpUrl) {
        try {
            MultimediaInfo info = new MultimediaObject(URI.create(httpUrl).toURL()).getInfo();
            long millis = info.getDuration();
            if (millis <= 0) {
                throw new IllegalStateException("No readable duration for " + safe(httpUrl));
            }
            return (int) Math.round(millis / 1000.0);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // ws.schild throws a checked EncoderException plus MalformedURLException; to a
            // caller they mean the same thing — the file could not be probed.
            throw new IllegalStateException("Could not probe " + safe(httpUrl), e);
        }
    }

    /** Presigned URLs carry a signature query string; keep it out of logs and messages. */
    private static String safe(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?…";
    }
}
