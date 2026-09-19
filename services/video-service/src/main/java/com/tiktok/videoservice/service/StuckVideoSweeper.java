package com.tiktok.videoservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoModeration;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.event.consumer.VideoStateUpdater;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Settles videos whose transcode result or moderation verdict never arrived — a consumer that
 * gave up to its dead-letter topic, a worker that died mid-job. Without this they stay in
 * PROCESSING or PENDING_MODERATION for good: invisible to everyone, and nothing tells the uploader.
 *
 * <p>A lost transcode becomes FAILED, which the uploader sees. A lost verdict becomes
 * PENDING_REVIEW, never PUBLISHED: a verdict that did not arrive is not a pass.
 *
 * <p>Both are ordinary guarded transitions, so a result that turns up late still applies to a
 * FAILED video, and runs on every replica are harmless — the second one's write finds the status
 * already moved and the entity refuses it.
 */
@Slf4j
@Component
public class StuckVideoSweeper {

    private static final String WHAT = "StuckVideoSweeper";

    private final VideoRepository videoRepository;
    private final VideoStateUpdater videoStateUpdater;
    private final Duration stuckAfter;

    public StuckVideoSweeper(VideoRepository videoRepository,
                             VideoStateUpdater videoStateUpdater,
                             @Value("${video.sweeper.stuck-after:PT3H}") Duration stuckAfter) {
        this.videoRepository = videoRepository;
        this.videoStateUpdater = videoStateUpdater;
        this.stuckAfter = stuckAfter;
    }

    @Scheduled(fixedDelayString = "${video.sweeper.interval-millis:600000}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(stuckAfter);

        for (Video video : videoRepository
                .findTop100ByStatusAndCreatedAtBeforeAndDeletedAtIsNullOrderByCreatedAtAsc(VideoStatus.PROCESSING, cutoff)) {
            log.warn("Video {} never finished transcoding, marking it failed", video.getId());
            videoStateUpdater.apply(video.getId(),
                    v -> v.markFailed("Transcoding did not finish. Try uploading the file again."),
                    videoRepository::updateFailed, WHAT);
        }

        for (Video video : videoRepository
                .findTop100ByStatusAndCreatedAtBeforeAndDeletedAtIsNullOrderByCreatedAtAsc(VideoStatus.PENDING_MODERATION, cutoff)) {
            log.warn("Video {} never got a moderation verdict, sending it to human review", video.getId());
            VideoModeration timedOut = VideoModeration.builder()
                    .verdict(ModerationVerdict.REVIEW)
                    .reason("No automatic verdict arrived within " + stuckAfter)
                    .checkedAt(Instant.now())
                    .build();
            videoStateUpdater.apply(video.getId(),
                    v -> v.applyModeration(timedOut),
                    videoRepository::updateModeration, WHAT);
        }
    }
}
