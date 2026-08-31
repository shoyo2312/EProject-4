package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoTranscodedEventProducer;
import com.tiktok.mediaworker.service.MediaCleanupService;
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
                transcodeService, mediaCleanupService, eventProducer, objectMapper, ATTEMPTS, 0L);
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void onMessage_transcodeSucceeds_publishesSuccessEvent() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("vid1", 1L, "My video", "s3://raw/vid1.mp4", List.of());

        when(transcodeService.transcode("vid1", "s3://raw/vid1.mp4"))
                .thenReturn(new TranscodeResult("http://minio/thumb.jpg", "http://minio/master.m3u8", 30));

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
        VideoPublishedEvent published = VideoPublishedEvent.of("vid2", 1L, "Broken video", "s3://raw/vid2.mp4", List.of());

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
                "vid7", 1L, "Too long", "s3://raw/vid7.mp4", java.util.List.of());

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
        VideoPublishedEvent published = VideoPublishedEvent.of("vid5", 1L, "Flaky", "s3://raw/vid5.mp4", List.of());

        when(transcodeService.transcode("vid5", "s3://raw/vid5.mp4"))
                .thenThrow(new RuntimeException("MinIO unreachable"))
                .thenReturn(new TranscodeResult("http://minio/thumb.jpg", "http://minio/master.m3u8", 30));

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
        VideoPublishedEvent published = VideoPublishedEvent.of("vid6", 1L, "Fine", "s3://raw/vid6.mp4", List.of());

        when(transcodeService.transcode("vid6", "s3://raw/vid6.mp4"))
                .thenReturn(new TranscodeResult("http://minio/thumb.jpg", "http://minio/master.m3u8", 30));
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
