package com.tiktok.videoservice.event.consumer;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Read, change, write-if-unchanged — the loop every status-changing consumer needs.
 *
 * <p>Transcode results and moderation decisions arrive on different topics, so they run on
 * different listener threads and genuinely overlap: transcoding takes minutes, which is ample
 * time for a moderator to act on the same video. Reading the document, deciding from
 * {@code video.getStatus()}, and writing the result back is three steps with two gaps, and a
 * takedown landing in either gap is lost — {@code Video.applyTranscodeOutcome} makes the right
 * decision from a status that is no longer true.
 *
 * <p>The write is therefore conditional on the status that was read, and losing means re-reading
 * rather than writing again. Re-reading is what makes the outcome correct rather than merely
 * safe: the second pass sees TAKEN_DOWN and records the transcode result as the state to restore
 * to, which is what the first pass would have done had it read the same document.
 *
 * <p>Retries are bounded because a video whose status changes three times inside one consumer's
 * read-write loop is not contention, it is something wrong; exhausting them throws, so the event
 * takes kafka-lib's retry-then-DLT path instead of being dropped in silence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoStateUpdater {

    private static final int MAX_ATTEMPTS = 3;

    private final VideoRepository videoRepository;

    /**
     * Soft-deleted videos are skipped rather than updated. An owner can delete between a
     * moderator's click and the event arriving, and transcoding outlives the delete window
     * many times over; writing anyway leaves a document that is both deleted and PUBLISHED, a
     * combination no read path expects and nothing later corrects.
     *
     * @param change what the event does to the video, e.g. {@code Video::markTakenDown}
     * @param write  the matching repository write, e.g. {@code videoRepository::updateStatus}
     * @param what   event name, for logs only
     */
    public void apply(String videoId,
                      Consumer<Video> change,
                      BiPredicate<Video, VideoStatus> write,
                      String what) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<Video> found = videoRepository.findByIdAndDeletedAtIsNull(videoId);
            if (found.isEmpty()) {
                log.warn("{} for unknown or deleted videoId={}", what, videoId);
                return;
            }

            Video video = found.get();
            VideoStatus statusWhenRead = video.getStatus();
            change.accept(video);

            if (write.test(video, statusWhenRead)) {
                return;
            }

            log.info("{} lost a status race on videoId={} (read {}), attempt {}/{}",
                    what, videoId, statusWhenRead, attempt, MAX_ATTEMPTS);
        }

        throw new IllegalStateException(
                "%s could not settle the status of videoId=%s in %d attempts"
                        .formatted(what, videoId, MAX_ATTEMPTS));
    }
}
