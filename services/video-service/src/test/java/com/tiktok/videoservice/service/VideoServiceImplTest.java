package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class VideoServiceImplTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoService videoService;

    @Autowired
    private VideoRepository videoRepository;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
    }

    @Test
    void publish_createsVideoWithProcessingStatus() {
        VideoResponse response = videoService.publish(1L,
                new CreateVideoRequest("My first video", "desc", "s3://raw/1.mp4", VideoVisibility.PUBLIC));

        assertThat(response.status()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(response.userId()).isEqualTo(1L);
    }

    @Test
    void getById_unknownVideo_throwsNotFound() {
        assertThatThrownBy(() -> videoService.getById(1L, "does-not-exist"))
                .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void getById_privateVideo_notOwner_throwsNotFound() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Private", null, "s3://raw/2.mp4", VideoVisibility.PRIVATE));

        assertThatThrownBy(() -> videoService.getById(2L, video.id()))
                .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void getById_privateVideo_owner_canView() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Private", null, "s3://raw/3.mp4", VideoVisibility.PRIVATE));

        VideoResponse fetched = videoService.getById(1L, video.id());
        assertThat(fetched.id()).isEqualTo(video.id());
    }

    @Test
    void getFeed_onlyReturnsPublishedPublicVideos() {
        VideoResponse processing = videoService.publish(1L,
                new CreateVideoRequest("Processing", null, "s3://raw/4.mp4", VideoVisibility.PUBLIC));
        VideoResponse published = videoService.publish(1L,
                new CreateVideoRequest("Published", null, "s3://raw/5.mp4", VideoVisibility.PUBLIC));
        markPublished(published.id());
        VideoResponse privatePublished = videoService.publish(1L,
                new CreateVideoRequest("Private published", null, "s3://raw/6.mp4", VideoVisibility.PRIVATE));
        markPublished(privatePublished.id());

        Page<VideoResponse> feed = videoService.getFeed(PageRequest.of(0, 10));

        assertThat(feed.getContent()).extracting(VideoResponse::id).containsExactly(published.id());
        assertThat(feed.getContent()).extracting(VideoResponse::id).doesNotContain(processing.id(), privatePublished.id());
    }

    @Test
    void delete_notOwner_throwsNotVideoOwner() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Mine", null, "s3://raw/7.mp4", VideoVisibility.PUBLIC));

        assertThatThrownBy(() -> videoService.delete(2L, video.id()))
                .isInstanceOf(NotVideoOwnerException.class);
    }

    @Test
    void delete_owner_softDeletesVideo() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Mine", null, "s3://raw/8.mp4", VideoVisibility.PUBLIC));

        videoService.delete(1L, video.id());

        assertThatThrownBy(() -> videoService.getById(1L, video.id()))
                .isInstanceOf(VideoNotFoundException.class);
    }

    private void markPublished(String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId).orElseThrow();
        video.markPublished(null, null, null);
        videoRepository.save(video);
    }
}
