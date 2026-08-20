package com.tiktok.mediaworker.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.mediaworker.event.producer.VideoTranscodedEventProducer;
import com.tiktok.mediaworker.service.TranscodeResult;
import com.tiktok.mediaworker.service.TranscodeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoPublishedEventConsumerTest {

    @Mock
    private TranscodeService transcodeService;

    @Mock
    private VideoTranscodedEventProducer eventProducer;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void onMessage_transcodeSucceeds_publishesSuccessEvent() throws Exception {
        VideoPublishedEventConsumer consumer = new VideoPublishedEventConsumer(transcodeService, eventProducer, objectMapper);
        VideoPublishedEvent published = VideoPublishedEvent.of("vid1", 1L, "My video", "s3://raw/vid1.mp4", List.of());

        when(transcodeService.transcode("vid1", "s3://raw/vid1.mp4"))
                .thenReturn(new TranscodeResult("http://minio/thumb.jpg", "http://minio/master.m3u8", 30));

        consumer.onMessage(objectMapper.writeValueAsString(published));

        ArgumentCaptor<VideoTranscodedEvent> captor = ArgumentCaptor.forClass(VideoTranscodedEvent.class);
        verify(eventProducer).publish(captor.capture());

        VideoTranscodedEvent result = captor.getValue();
        assertThat(result.videoId()).isEqualTo("vid1");
        assertThat(result.success()).isTrue();
        assertThat(result.thumbnailUrl()).isEqualTo("http://minio/thumb.jpg");
        assertThat(result.hlsUrl()).isEqualTo("http://minio/master.m3u8");
    }

    @Test
    void onMessage_transcodeThrows_publishesFailureEvent() throws Exception {
        VideoPublishedEventConsumer consumer = new VideoPublishedEventConsumer(transcodeService, eventProducer, objectMapper);
        VideoPublishedEvent published = VideoPublishedEvent.of("vid2", 1L, "Broken video", "s3://raw/vid2.mp4", List.of());

        when(transcodeService.transcode(anyString(), anyString())).thenThrow(new RuntimeException("MinIO unreachable"));

        consumer.onMessage(objectMapper.writeValueAsString(published));

        ArgumentCaptor<VideoTranscodedEvent> captor = ArgumentCaptor.forClass(VideoTranscodedEvent.class);
        verify(eventProducer).publish(captor.capture());

        VideoTranscodedEvent result = captor.getValue();
        assertThat(result.videoId()).isEqualTo("vid2");
        assertThat(result.success()).isFalse();
        assertThat(result.failureReason()).isEqualTo("MinIO unreachable");
    }
}
