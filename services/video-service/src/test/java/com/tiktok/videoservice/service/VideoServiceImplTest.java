package com.tiktok.videoservice.service;

import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.AlreadyPublishedException;
import com.tiktok.videoservice.exception.ForeignUploadException;
import com.tiktok.videoservice.exception.InvalidFeedCursorException;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.UnsupportedUploadTypeException;
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

import java.util.ArrayList;
import java.util.List;

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

    /**
     * The point of the assertion on fileUrl: it is fed straight back into publish() below, where
     * CreateVideoRequest.rawFileUrl is checked by @ValidMediaUrl against app.media.allowed-buckets.
     * A bucket or scheme change on either side silently makes every upload unpublishable, and this
     * is the only place the two ends meet.
     */
    @Test
    void createUploadUrl_returnsPresignedPutAndAStorageUrlPublishAccepts() {
        UploadUrlResponse response = videoService.createUploadUrl(42L, new UploadUrlRequest("video/mp4"));

        assertThat(response.fileUrl()).startsWith("s3://video-media/raw/42/").endsWith(".mp4");
        assertThat(response.uploadUrl())
                .contains("/video-media/raw/42/")
                .contains("X-Amz-Signature");
        assertThat(response.expiresInSeconds()).isPositive();

        String key = response.fileUrl().substring("s3://video-media/".length());
        assertThat(response.uploadUrl()).contains(key);

        VideoResponse published = videoService.publish(42L,
                new CreateVideoRequest("uploaded", null, response.fileUrl(), VideoVisibility.PUBLIC, List.of()));
        assertThat(videoRepository.findById(published.id()).orElseThrow().getRawFileUrl())
                .isEqualTo(response.fileUrl());
    }

    @Test
    void createUploadUrl_rejectsATypeTheTranscoderCannotRead() {
        assertThatThrownBy(() -> videoService.createUploadUrl(42L, new UploadUrlRequest("application/zip")))
                .isInstanceOf(UnsupportedUploadTypeException.class);
    }

    @Test
    void publish_createsVideoWithProcessingStatus() {
        VideoResponse response = videoService.publish(1L,
                new CreateVideoRequest("My first video", "desc", "s3://video-media/raw/1/first.mp4", VideoVisibility.PUBLIC, List.of()));

        assertThat(response.status()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(response.userId()).isEqualTo(1L);
    }

    /**
     * {@code @ValidMediaUrl} proves the URL points at our storage, not whose file it is. Upload
     * keys are namespaced by uploader, so without this check publishing someone else's raw key
     * republishes their video under the caller's name — the pipeline does the rest of the work.
     */
    @Test
    void publish_someoneElsesUploadKey_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Not mine", null, "s3://video-media/raw/2/stolen.mp4", VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /** Same key over the CDN host instead — one segment further in, equally not theirs. */
    @Test
    void publish_someoneElsesUploadKeyOverHttps_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Not mine", null, "https://cdn.tiktok-clone.local/video-media/raw/2/stolen.mp4",
                VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /**
     * The check reads the segment after the first {@code raw}, so a key that starts under the
     * caller's own prefix and then climbs back out of it passes on the raw string. java.net.URI
     * leaves dot segments exactly where they are; every HTTP client that later fetches the URL
     * resolves them, which is how "mine" on the way in becomes someone else's file on the way
     * out.
     */
    @Test
    void publish_aKeyThatClimbsOutOfTheCallersPrefix_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Traversal", null,
                "https://cdn.tiktok-clone.local/video-media/raw/1/../2/stolen.mp4",
                VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /** The same trick over s3://, where the bucket sits in the authority instead of the path. */
    @Test
    void publish_anS3KeyThatClimbsOutOfTheCallersPrefix_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Traversal", null, "s3://video-media/raw/1/../2/stolen.mp4",
                VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /**
     * A media type may carry parameters. Rejecting {@code video/mp4; charset=utf-8} tells the
     * client its file is unsupported when the type is one this service accepts.
     */
    @Test
    void createUploadUrl_acceptsAMediaTypeCarryingParameters() {
        assertThat(videoService.createUploadUrl(42L, new UploadUrlRequest("video/mp4; charset=utf-8")).fileUrl())
                .endsWith(".mp4");
    }

    /**
     * One upload is one video. A client retrying POST /videos after a timeout would otherwise get
     * a second document, a second VideoPublishedEvent, and a second transcode job off one file,
     * with nothing downstream able to tell the copies apart.
     */
    @Test
    void publish_aRawFileThatAlreadyHasAVideo_isRejected() {
        CreateVideoRequest request = new CreateVideoRequest(
                "Retried", null, "s3://video-media/raw/1/again.mp4", VideoVisibility.PUBLIC, List.of());
        videoService.publish(1L, request);

        assertThatThrownBy(() -> videoService.publish(1L, request))
                .isInstanceOf(AlreadyPublishedException.class);
    }

    @Test
    void publish_aKeyThisServiceNeverIssued_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Transcoded output", null, "s3://video-media/hls/2/master.m3u8", VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /**
     * Everything downstream compares tags as plain strings — a candidate generator matching a
     * viewer's affinity against a video's tags would read "#Dance", "dance " and "DANCE" as three
     * unrelated interests, splitting the signal exactly where it is thinnest. Normalising at the
     * only place tags enter the system is what keeps every later comparison honest.
     */
    @Test
    void publish_normalizesTags() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "Tagged", null, "s3://video-media/raw/1/tagged.mp4", VideoVisibility.PUBLIC,
                List.of("#Dance", " dance ", "Food", "", "  ")));

        assertThat(response.tags()).containsExactly("dance", "food");
        assertThat(videoRepository.findById(response.id()).orElseThrow().getTags())
                .containsExactly("dance", "food");
    }

    @Test
    void publish_withoutTags_storesAnEmptyListNotNull() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "Untagged", null, "s3://video-media/raw/1/untagged.mp4", VideoVisibility.PUBLIC, null));

        assertThat(response.tags()).isEmpty();
    }

    @Test
    void getById_unknownVideo_throwsNotFound() {
        assertThatThrownBy(() -> videoService.getById(1L, "does-not-exist"))
                .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void getById_privateVideo_notOwner_throwsNotFound() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Private", null, "s3://video-media/raw/1/2.mp4", VideoVisibility.PRIVATE, List.of()));

        assertThatThrownBy(() -> videoService.getById(2L, video.id()))
                .isInstanceOf(VideoNotFoundException.class);
    }

    @Test
    void getById_privateVideo_owner_canView() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Private", null, "s3://video-media/raw/1/3.mp4", VideoVisibility.PRIVATE, List.of()));

        VideoResponse fetched = videoService.getById(1L, video.id());
        assertThat(fetched.id()).isEqualTo(video.id());
    }

    @Test
    void getFeed_onlyReturnsPublishedPublicVideos() {
        VideoResponse processing = videoService.publish(1L,
                new CreateVideoRequest("Processing", null, "s3://video-media/raw/1/4.mp4", VideoVisibility.PUBLIC, List.of()));
        VideoResponse published = videoService.publish(1L,
                new CreateVideoRequest("Published", null, "s3://video-media/raw/1/5.mp4", VideoVisibility.PUBLIC, List.of()));
        markPublished(published.id());
        VideoResponse privatePublished = videoService.publish(1L,
                new CreateVideoRequest("Private published", null, "s3://video-media/raw/1/6.mp4", VideoVisibility.PRIVATE, List.of()));
        markPublished(privatePublished.id());

        CursorPage<VideoResponse> feed = videoService.getFeed(null, 10);

        assertThat(feed.items()).extracting(VideoResponse::id).containsExactly(published.id());
        assertThat(feed.items()).extracting(VideoResponse::id).doesNotContain(processing.id(), privatePublished.id());
        assertThat(feed.nextCursor()).as("one video, one page — nothing left to page to").isNull();
    }

    /**
     * The property a keyset must hold and an offset page need not: walking the whole feed one page
     * at a time visits every video exactly once. A cursor off by one row in either direction shows
     * up here as a duplicate or a gap — the failure the tiebreak in FeedCursor exists to prevent —
     * so the videos are seeded in one tight loop, where sharing a createdAt millisecond is likely
     * rather than theoretical.
     */
    @Test
    void getFeed_pagingByCursor_visitsEveryVideoExactlyOnce() {
        List<String> publishedIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            VideoResponse video = videoService.publish(1L, new CreateVideoRequest(
                    "video-" + i, null, "s3://video-media/raw/1/" + i + ".mp4", VideoVisibility.PUBLIC, List.of()));
            markPublished(video.id());
            publishedIds.add(video.id());
        }

        List<String> walked = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<VideoResponse> page = videoService.getFeed(cursor, 7);
            walked.addAll(page.items().stream().map(VideoResponse::id).toList());
            cursor = page.nextCursor();
        } while (cursor != null);

        // Newest first, so the walk is the seeding order reversed.
        assertThat(walked).containsExactlyElementsOf(publishedIds.reversed());
    }

    @Test
    void getFeed_sizeAboveMax_isClampedRatherThanRefused() {
        for (int i = 0; i < 55; i++) {
            VideoResponse video = videoService.publish(1L, new CreateVideoRequest(
                    "video-" + i, null, "s3://video-media/raw/1/" + i + ".mp4", VideoVisibility.PUBLIC, List.of()));
            markPublished(video.id());
        }

        assertThat(videoService.getFeed(null, 1000).items()).hasSize(50);
    }

    @Test
    void getFeed_unusableCursor_isRejected() {
        assertThatThrownBy(() -> videoService.getFeed("not-a-cursor", 10))
                .isInstanceOf(InvalidFeedCursorException.class);
    }

    @Test
    void listByUser_otherViewer_onlyReturnsPublishedPublicVideos() {
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());
        VideoResponse processing = publishAs(1L, "Processing", VideoVisibility.PUBLIC);
        VideoResponse privatePublished = publishAs(1L, "Private", VideoVisibility.PRIVATE);
        markPublished(privatePublished.id());
        VideoResponse takenDown = publishAs(1L, "Taken down", VideoVisibility.PUBLIC);
        markPublished(takenDown.id());
        markTakenDown(takenDown.id());

        Page<VideoResponse> listed = videoService.listByUser(2L, 1L, PageRequest.of(0, 10));

        assertThat(listed.getContent()).extracting(VideoResponse::id).containsExactly(published.id());
        assertThat(listed.getContent()).extracting(VideoResponse::id)
                .doesNotContain(processing.id(), privatePublished.id(), takenDown.id());
    }

    @Test
    void listByUser_anonymousViewer_onlyReturnsPublishedPublicVideos() {
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());
        VideoResponse privatePublished = publishAs(1L, "Private", VideoVisibility.PRIVATE);
        markPublished(privatePublished.id());

        Page<VideoResponse> listed = videoService.listByUser(null, 1L, PageRequest.of(0, 10));

        assertThat(listed.getContent()).extracting(VideoResponse::id).containsExactly(published.id());
    }

    @Test
    void listByUser_owner_seesOwnPrivateAndUnpublishedVideos() {
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());
        VideoResponse processing = publishAs(1L, "Processing", VideoVisibility.PUBLIC);
        VideoResponse privateVideo = publishAs(1L, "Private", VideoVisibility.PRIVATE);

        Page<VideoResponse> listed = videoService.listByUser(1L, 1L, PageRequest.of(0, 10));

        assertThat(listed.getContent()).extracting(VideoResponse::id)
                .containsExactlyInAnyOrder(published.id(), processing.id(), privateVideo.id());
    }

    @Test
    void listByUser_owner_stillExcludesDeletedVideos() {
        VideoResponse deleted = publishAs(1L, "Deleted", VideoVisibility.PUBLIC);
        videoService.delete(1L, deleted.id());

        Page<VideoResponse> listed = videoService.listByUser(1L, 1L, PageRequest.of(0, 10));

        assertThat(listed.getContent()).isEmpty();
    }

    @Test
    void delete_notOwner_throwsNotVideoOwner() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Mine", null, "s3://video-media/raw/1/7.mp4", VideoVisibility.PUBLIC, List.of()));

        assertThatThrownBy(() -> videoService.delete(2L, video.id()))
                .isInstanceOf(NotVideoOwnerException.class);
    }

    @Test
    void delete_owner_softDeletesVideo() {
        VideoResponse video = videoService.publish(1L,
                new CreateVideoRequest("Mine", null, "s3://video-media/raw/1/8.mp4", VideoVisibility.PUBLIC, List.of()));

        videoService.delete(1L, video.id());

        assertThatThrownBy(() -> videoService.getById(1L, video.id()))
                .isInstanceOf(VideoNotFoundException.class);
    }

    private VideoResponse publishAs(long userId, String title, VideoVisibility visibility) {
        return videoService.publish(userId,
                new CreateVideoRequest(title, null, "s3://video-media/raw/" + userId + "/" + title.replace(' ', '-') + ".mp4", visibility, List.of()));
    }

    private void markPublished(String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId).orElseThrow();
        video.markPublished(null, null, null);
        videoRepository.save(video);
    }

    private void markTakenDown(String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId).orElseThrow();
        video.markTakenDown();
        videoRepository.updateStatus(video, VideoStatus.PUBLISHED);
    }
}
