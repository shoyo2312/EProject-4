package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoPurgedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoTranscodedEventProducer;
import com.tiktok.mediaworker.service.MediaCleanupService;
import com.tiktok.mediaworker.service.MediaQuarantineService;
import com.tiktok.mediaworker.service.MediaRejectedException;
import com.tiktok.mediaworker.service.TranscodeResult;
import com.tiktok.mediaworker.service.TranscodeService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * video.video-events carries several flat JSON objects with no type field, so routing is on the
 * eventType header video-service sets — see its VideoEventPublisher.
 *
 * <p>Media is erased on VideoPurgedEvent, not VideoDeletedEvent. The deletion goes out the moment
 * the owner deletes and is for the index consumers; the purge follows once the trash window
 * (video-service's {@code video.trash.retention}) has run out, so a moderator can still play the
 * video from the console in between. Acting on the deletion here would empty the trash on the
 * spot.
 *
 * <p>No inbox/idempotency table here: media-worker keeps no state of its own. Re-processing
 * the same VideoPublishedEvent just overwrites the same MinIO keys with the same content
 * (safe no-op), re-processing a purge removes objects that are already gone, and the durable
 * mutation the transcode triggers happens in video-service's own inbox-guarded consumer of
 * VideoTranscodedEvent — that's where duplicate delivery actually needs to be rejected.
 */
@Component
@Slf4j
public class VideoEventConsumer {

    private static final String VIDEO_PUBLISHED = "VideoPublishedEvent";
    private static final String VIDEO_PURGED = "VideoPurgedEvent";

    private final TranscodeService transcodeService;
    private final MediaCleanupService mediaCleanupService;
    private final VideoTranscodedEventProducer eventProducer;
    private final MediaQuarantineService quarantine;
    private final ObjectMapper objectMapper;
    private final int transcodeAttempts;
    private final long retryBackoffMillis;

    public VideoEventConsumer(TranscodeService transcodeService,
                              MediaCleanupService mediaCleanupService,
                              VideoTranscodedEventProducer eventProducer,
                              MediaQuarantineService quarantine,
                              ObjectMapper objectMapper,
                              @Value("${media.transcode.attempts:3}") int transcodeAttempts,
                              @Value("${media.transcode.retry-backoff-millis:2000}") long retryBackoffMillis) {
        this.transcodeService = transcodeService;
        this.mediaCleanupService = mediaCleanupService;
        this.eventProducer = eventProducer;
        this.quarantine = quarantine;
        this.objectMapper = objectMapper;
        this.transcodeAttempts = transcodeAttempts;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    @KafkaListener(topics = "video.video-events", groupId = "media-worker")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        // Absent header means a producer older than the deletion event, and everything that topic
        // carried then was a publication. Nothing else writes to it, so this is not a guess.
        String eventType = eventTypeHeader == null
                ? VIDEO_PUBLISHED
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (VIDEO_PUBLISHED.equals(eventType)) {
            handlePublished(objectMapper.readValue(payload, VideoPublishedEvent.class));
        } else if (VIDEO_PURGED.equals(eventType)) {
            VideoPurgedEvent event = objectMapper.readValue(payload, VideoPurgedEvent.class);
            mediaCleanupService.deleteMediaFor(event.videoId(), event.rawFileUrl());
        } else {
            // VideoDeletedEvent lands here too, on purpose — see the class comment.
            log.debug("Ignoring video eventType={}", eventType);
        }
    }

    /**
     * The publish sits outside the transcode's own error handling on purpose: a broker that will
     * not take the success event has to redeliver the whole message, not be reported as a video
     * that failed to transcode. Folded together, a Kafka problem wrote FAILED onto a video whose
     * media was sitting in the bucket, finished and correct.
     */
    private void handlePublished(VideoPublishedEvent event) {
        // ponytail: check-then-act. Same-key events share a partition, so the only overlap is a
        // rebalance mid-transcode, and video-service ignores the duplicate result that produces.
        if (transcodeService.alreadyTranscoded(event.videoId())) {
            log.info("Video {} was already transcoded, skipping the redelivered publication", event.videoId());
            return;
        }
        VideoTranscodedEvent result = transcodeWithRetries(event);
        // A takedown that landed while this was encoding had nothing to move yet, and the output
        // was just written to the public prefixes. Its marker is still there, so finish its job.
        if (result.success() && quarantine.isQuarantined(event.videoId())) {
            quarantine.quarantine(event.videoId());
        }
        eventProducer.publish(result);
        transcodeService.recordTranscoded(event.videoId());
    }

    /**
     * FAILED is terminal — nothing re-runs a transcode once video-service has recorded it, and no
     * screen offers the uploader a retry. Reporting it off a single attempt therefore turns a
     * two-second MinIO blip into a permanently broken upload. These attempts are what separate
     * "storage was briefly unreachable" from "this file cannot be transcoded", a distinction the
     * exception type cannot make: the same IOException covers both.
     */
    private VideoTranscodedEvent transcodeWithRetries(VideoPublishedEvent event) {
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= transcodeAttempts; attempt++) {
            try {
                TranscodeResult result = transcodeService.transcode(event.videoId(), event.rawFileUrl());
                return VideoTranscodedEvent.success(
                        event.videoId(), result.thumbnailUrl(), result.previewUrl(),
                        result.hlsUrl(), result.durationSeconds(), result.width(), result.height());
            } catch (MediaRejectedException e) {
                // Permanently unacceptable — retrying re-probes the same file to the same answer.
                log.warn("Rejecting video {}: {}", event.videoId(), e.getMessage());
                return VideoTranscodedEvent.failure(event.videoId(), e.getMessage());
            } catch (Exception e) {
                lastFailure = e;
                log.warn("Transcode of video {} failed on attempt {}/{}: {}",
                        event.videoId(), attempt, transcodeAttempts, e.getMessage());
                if (attempt < transcodeAttempts) {
                    pause();
                }
            }
        }

        log.error("Transcode of video {} failed {} times, reporting it as failed",
                event.videoId(), transcodeAttempts, lastFailure);
        // lastFailure.getMessage() is an internal storage/probe string (endpoint, bucket, key) and
        // this reason is shown to the uploader. Only MediaRejectedException carries a message
        // written for a human; the transient path gets a generic one.
        return VideoTranscodedEvent.failure(event.videoId(),
                "Transcoding failed after %d attempts. Try uploading the file again.".formatted(transcodeAttempts));
    }

    /**
     * ponytail: a fixed pause on the listener thread, which stalls this partition while it waits.
     * Fine while one worker handles the whole topic; move to a delayed retry topic if transcoding
     * ever has to keep flowing past a stuck video.
     */
    private void pause() {
        try {
            Thread.sleep(retryBackoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted between transcode attempts", e);
        }
    }
}
