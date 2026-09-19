package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoTranscodedEventProducer;
import com.tiktok.mediaworker.service.MediaCleanupService;
import com.tiktok.mediaworker.service.MediaQuarantineService;
import com.tiktok.mediaworker.service.TranscodeResult;
import com.tiktok.mediaworker.service.TranscodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoEventConsumerTest {

    @Mock
    private TranscodeService transcodeService;

    @Mock
    private MediaCleanupService mediaCleanupService;

    @Mock
    private VideoTranscodedEventProducer eventProducer;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final int ATTEMPTS = 3;

    /** No backoff in tests: the pause is real time and proves nothing the attempt count doesn't. */
    private VideoEventConsumer consumer() {
        return new VideoEventConsumer(
                transcodeService, mediaCleanupService, eventProducer, quarantine, objectMapper, ATTEMPTS, 0L);
    }

    @Mock
    private MediaQuarantineService quarantine;

    /**
     * Transcoding takes minutes, and a moderator acting on a fresh upload is routinely overtaken
     * by it: the takedown quarantines nothing because nothing exists yet, and then the transcode
     * writes its output to the public prefixes.
     */
    @Test
    void onMessage_transcodeFinishingAfterATakedown_quarantinesWhatItJustWrote() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid9", 1L, "t", null, "s3://raw/vid9.mp4", "PUBLIC", List.of());
        when(transcodeService.transcode("vid9", "s3://raw/vid9.mp4"))
                .thenReturn(new TranscodeResult("t.jpg", null, "m.mp4", 3, 1, 1));
        when(quarantine.isQuarantined("vid9")).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        verify(quarantine).quarantine("vid9");
    }

    @Test
    void onMessage_ordinaryTranscode_leavesTheMediaPublic() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid8", 1L, "t", null, "s3://raw/vid8.mp4", "PUBLIC", List.of());
        when(transcodeService.transcode("vid8", "s3://raw/vid8.mp4"))
                .thenReturn(new TranscodeResult("t.jpg", null, "m.mp4", 3, 1, 1));

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        verify(quarantine, never()).quarantine(anyString());
    }

    /**
     * The same VideoPublishedEvent arrives again whenever the outbox resends it or a rebalance
     * replays the partition. Each copy re-encoded the whole video and published a second result
     * with a fresh eventId, which video-service could not tell from the first.
     */
    @Test
    void onMessage_publicationAlreadyTranscoded_isNotTranscodedAgain() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid7", 1L, "t", null, "s3://raw/vid7.mp4", "PUBLIC", List.of());
        when(transcodeService.alreadyTranscoded("vid7")).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        verify(transcodeService, never()).transcode(anyString(), anyString());
        verifyNoInteractions(eventProducer);
    }

    /**
     * Recorded only after the result is out: a crash in between must redo the transcode rather
     * than leave a video that nothing will ever report on.
     */
    @Test
    void onMessage_transcodeReported_isRememberedAfterThePublish() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid6", 1L, "t", null, "s3://raw/vid6.mp4", "PUBLIC", List.of());
        when(transcodeService.transcode("vid6", "s3://raw/vid6.mp4"))
                .thenReturn(new TranscodeResult("t.jpg", null, "m.mp4", 3, 1, 1));

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(eventProducer, transcodeService);
        order.verify(eventProducer).publish(org.mockito.ArgumentMatchers.any());
        order.verify(transcodeService).recordTranscoded("vid6");
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void onMessage_transcodeSucceeds_publishesSuccessEvent() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid1", 1L, "My video", null, "s3://raw/vid1.mp4", "PUBLIC", List.of());

        when(transcodeService.transcode("vid1", "s3://raw/vid1.mp4"))
                .thenReturn(new TranscodeResult("http://minio/thumb.jpg", "http://minio/preview.webp", "http://minio/master.m3u8", 30, 1080, 1920));

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        ArgumentCaptor<VideoTranscodedEvent> captor = ArgumentCaptor.forClass(VideoTranscodedEvent.class);
        verify(eventProducer).publish(captor.capture());

        VideoTranscodedEvent result = captor.getValue();
        assertThat(result.videoId()).isEqualTo("vid1");
        assertThat(result.success()).isTrue();
        assertThat(result.thumbnailUrl()).isEqualTo("http://minio/thumb.jpg");
        assertThat(result.hlsUrl()).isEqualTo("http://minio/master.m3u8");
    }

    @Test
    void onMessage_transcodeKeepsThrowing_publishesFailureEvent() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid2", 1L, "Broken video", null, "s3://raw/vid2.mp4", "PUBLIC", List.of());

        when(transcodeService.transcode(anyString(), anyString()))
                .thenThrow(new RuntimeException("http://minio:9000/video-media/raw/7/vid2.mp4 unreachable"));

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        ArgumentCaptor<VideoTranscodedEvent> captor = ArgumentCaptor.forClass(VideoTranscodedEvent.class);
        verify(eventProducer).publish(captor.capture());

        VideoTranscodedEvent result = captor.getValue();
        assertThat(result.videoId()).isEqualTo("vid2");
        assertThat(result.success()).isFalse();
        // The transient reason is shown to the uploader: it must be generic, never the internal
        // exception string (endpoint, bucket, key).
        assertThat(result.failureReason())
                .isEqualTo("Transcoding failed after 3 attempts. Try uploading the file again.");
        assertThat(result.failureReason()).doesNotContain("minio", "video-media");
        verify(transcodeService, times(ATTEMPTS)).transcode(anyString(), anyString());
    }

    @Test
    void onMessage_transcodeRejectsTheFile_failsOnceWithNoRetry() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of(
                "vid7", 1L, "Too long", null, "s3://raw/vid7.mp4", "PUBLIC", java.util.List.of());

        when(transcodeService.transcode("vid7", "s3://raw/vid7.mp4"))
                .thenThrow(new com.tiktok.mediaworker.service.MediaRejectedException(
                        "Video is 12m30s; the maximum is 10m00s."));

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        ArgumentCaptor<VideoTranscodedEvent> captor = ArgumentCaptor.forClass(VideoTranscodedEvent.class);
        verify(eventProducer).publish(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().failureReason()).isEqualTo("Video is 12m30s; the maximum is 10m00s.");
        verify(transcodeService, times(1)).transcode(anyString(), anyString());
    }

    /**
     * FAILED is terminal and nothing offers a retry, so a storage blip must not produce it. The
     * distinction cannot come from the exception — a brief outage and an unreadable file raise the
     * same one — so it comes from trying again.
     */
    @Test
    void onMessage_transcodeRecoversOnASecondAttempt_publishesSuccess() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid5", 1L, "Flaky", null, "s3://raw/vid5.mp4", "PUBLIC", List.of());

        when(transcodeService.transcode("vid5", "s3://raw/vid5.mp4"))
                .thenThrow(new RuntimeException("MinIO unreachable"))
                .thenReturn(new TranscodeResult("http://minio/thumb.jpg", "http://minio/preview.webp", "http://minio/master.m3u8", 30, 1080, 1920));

        consumer().onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        ArgumentCaptor<VideoTranscodedEvent> captor = ArgumentCaptor.forClass(VideoTranscodedEvent.class);
        verify(eventProducer).publish(captor.capture());
        assertThat(captor.getValue().success()).isTrue();
    }

    /**
     * A broker that will not take the result is not a video that failed to transcode. Reporting it
     * as one wrote FAILED onto a video whose media was already in the bucket, finished and
     * correct; throwing instead redelivers the message and the transcode runs again over the same
     * keys.
     */
    @Test
    void onMessage_publishFails_doesNotReportTheVideoAsFailed() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid6", 1L, "Fine", null, "s3://raw/vid6.mp4", "PUBLIC", List.of());

        when(transcodeService.transcode("vid6", "s3://raw/vid6.mp4"))
                .thenReturn(new TranscodeResult("http://minio/thumb.jpg", "http://minio/preview.webp", "http://minio/master.m3u8", 30, 1080, 1920));
        doThrow(new IllegalStateException("broker refused")).when(eventProducer).publish(any());

        String payload = objectMapper.writeValueAsString(published);
        assertThatThrownBy(() -> consumer().onMessage(payload, header("VideoPublishedEvent")))
                .isInstanceOf(IllegalStateException.class);

        verify(eventProducer, never()).publish(argThat(event -> !event.success()));
    }

    /**
     * Nothing else in the system knows where a video's objects live, so a deletion that does not
     * reach here is storage nobody will ever reclaim or even be able to find.
     */
    @Test
    void onMessage_deletion_removesTheMedia() throws Exception {
        VideoDeletedEvent deleted = VideoDeletedEvent.of("vid3", 1L, "s3://video-media/raw/1/vid3.mp4");

        consumer().onMessage(objectMapper.writeValueAsString(deleted), header("VideoDeletedEvent"));

        verify(mediaCleanupService).deleteMediaFor("vid3", "s3://video-media/raw/1/vid3.mp4");
        verifyNoInteractions(transcodeService, eventProducer);
    }

    /**
     * Routing is header-only. Without it the deletion payload still parses as a publication —
     * Jackson has no reason to object — and the worker transcodes a null rawFileUrl for a video
     * that has just been removed.
     */
    @Test
    void onMessage_deletion_isNotTranscoded() throws Exception {
        VideoDeletedEvent deleted = VideoDeletedEvent.of("vid4", 1L, "s3://video-media/raw/1/vid4.mp4");

        consumer().onMessage(objectMapper.writeValueAsString(deleted), header("VideoDeletedEvent"));

        verifyNoInteractions(transcodeService);
    }
}
