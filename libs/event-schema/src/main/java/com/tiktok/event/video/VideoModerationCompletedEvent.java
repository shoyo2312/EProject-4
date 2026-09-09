package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The outcome of automatic content moderation, published by media-worker once per successfully
 * transcoded video and consumed by video-service, which turns the verdict into a status.
 *
 * <p>Separate from VideoTranscodedEvent on purpose. Transcoding and moderation fail for unrelated
 * reasons and at unrelated times — a model that is down says nothing about whether the file is
 * playable — and folding the verdict into the transcode result would mean a video could not be
 * recorded as transcoded until moderation had also answered. Two events let the video reach
 * PENDING_MODERATION with its media intact and wait there.
 *
 * <p>The scores travel with the verdict rather than staying in moderation-service's logs, because
 * the thresholds are going to be tuned against real uploads and that is impossible without seeing
 * what the model actually said about the videos a moderator later agreed or disagreed with.
 *
 * @param verdict          what to do with the video
 * @param label            the model's label behind {@code maxScore}, e.g. {@code nsfw}
 * @param maxScore         the highest score any sampled frame scored for {@code label}
 * @param suspiciousFrames how many sampled frames scored above the review threshold — the
 *                         difference between one bad still and a video that is bad throughout
 * @param totalFrames      how many frames were actually sampled and scored
 * @param model            model identifier, e.g. {@code Falconsai/nsfw_image_detection}
 * @param modelVersion     the moderation-service build behind this decision, so a verdict can be
 *                         attributed to a specific set of weights and thresholds after a change
 * @param reason           human-readable note; null when the model answered normally, set when
 *                         the verdict is REVIEW because the check could not be completed
 */
public record VideoModerationCompletedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        ModerationVerdict verdict,
        String label,
        double maxScore,
        int suspiciousFrames,
        int totalFrames,
        long processingTimeMs,
        String model,
        String modelVersion,
        String reason
) implements DomainEvent {

    public static VideoModerationCompletedEvent of(String videoId, ModerationVerdict verdict, String label,
                                                   double maxScore, int suspiciousFrames, int totalFrames,
                                                   long processingTimeMs, String model, String modelVersion,
                                                   String reason) {
        return new VideoModerationCompletedEvent(UUID.randomUUID().toString(), Instant.now(), videoId,
                verdict, label, maxScore, suspiciousFrames, totalFrames, processingTimeMs,
                model, modelVersion, reason);
    }

    /**
     * The fail-safe outcome: moderation could not run, so the video is neither published nor
     * removed on a guess — it goes to the admin queue with the reason attached.
     */
    public static VideoModerationCompletedEvent unavailable(String videoId, String reason) {
        return of(videoId, ModerationVerdict.REVIEW, null, 0.0, 0, 0, 0L, null, null, reason);
    }
}
