package com.tiktok.videoservice.entity;

import com.tiktok.event.video.ModerationVerdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transcode and moderation results can arrive twice: media-worker's publish can be retried after
 * an acknowledgement times out, and each result carries a fresh random eventId, so the inbox does
 * not recognise the second copy. Applied again, a duplicate walked a live video back to
 * PENDING_MODERATION — off every read path — and then let a second verdict overrule the first.
 */
class VideoPipelineReplayTest {

    @Test
    void aDuplicateTranscodeResult_doesNotPullAPublishedVideoBackIntoModeration() {
        Video video = video(VideoStatus.PUBLISHED);

        video.markTranscoded("t", "p", "h", 10, 1080, 1920);

        assertThat(video.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
    }

    @Test
    void aDuplicateFailure_doesNotFailAPublishedVideo() {
        Video video = video(VideoStatus.PUBLISHED);

        video.markFailed("late");

        assertThat(video.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
    }

    @Test
    void aSecondVerdict_doesNotOverruleTheFirst() {
        Video video = video(VideoStatus.PUBLISHED);

        video.applyModeration(verdict(ModerationVerdict.REVIEW));

        assertThat(video.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
        assertThat(video.getModeration()).isNull();
    }

    @Test
    void aTakenDownVideo_keepsTheStatusItWillBeRestoredTo() {
        Video video = video(VideoStatus.PUBLISHED);
        video.markTakenDown("spam");

        video.markTranscoded("t", "p", "h", 10, 1080, 1920);

        video.markRestored();
        assertThat(video.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
    }

    /** The normal pipeline, and the one retry it has always allowed, still go through. */
    @Test
    void theFirstResultOfEachStage_isApplied() {
        Video video = video(VideoStatus.PROCESSING);
        video.markTranscoded("t", "p", "h", 10, 1080, 1920);
        assertThat(video.getStatus()).isEqualTo(VideoStatus.PENDING_MODERATION);

        video.applyModeration(verdict(ModerationVerdict.APPROVED));
        assertThat(video.getStatus()).isEqualTo(VideoStatus.PUBLISHED);

        Video failed = video(VideoStatus.FAILED);
        failed.markTranscoded("t", "p", "h", 10, 1080, 1920);
        assertThat(failed.getStatus()).isEqualTo(VideoStatus.PENDING_MODERATION);
    }

    @Test
    void aTranscodeFinishingDuringATakedown_isStillRecordedForTheRestore() {
        Video video = video(VideoStatus.PROCESSING);
        video.markTakenDown("spam");

        video.markTranscoded("t", "p", "h", 10, 1080, 1920);

        assertThat(video.getStatus()).isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(video.getStatusBeforeTakedown()).isEqualTo(VideoStatus.PENDING_MODERATION);
    }

    private static Video video(VideoStatus status) {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1/t.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(status)
                .build();
    }

    private static VideoModeration verdict(ModerationVerdict verdict) {
        return VideoModeration.builder().verdict(verdict).build();
    }
}
