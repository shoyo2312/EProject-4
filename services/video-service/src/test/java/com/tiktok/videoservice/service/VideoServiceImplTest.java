package com.tiktok.videoservice.service;

import com.tiktok.videoservice.client.FeedKeywords;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.FeedKind;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.videoservice.entity.OwnerBan;
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.entity.PipelineState;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoModeration;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoTextModeration;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.AlreadyPublishedException;
import com.tiktok.videoservice.exception.CaptionRejectedException;
import com.tiktok.videoservice.exception.ForeignUploadException;
import com.tiktok.videoservice.exception.InvalidFeedCursorException;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.PrivateAccountException;
import com.tiktok.videoservice.exception.TooManyFollowedUsersException;
import com.tiktok.videoservice.exception.UnsupportedUploadTypeException;
import com.tiktok.videoservice.exception.UploadNotFoundException;
import com.tiktok.videoservice.exception.UploadUrlUnavailableException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.repository.OwnerBanRepository;
import com.tiktok.videoservice.repository.OwnerPrivacyRepository;
import com.tiktok.videoservice.repository.VideoRepository;
import io.minio.MinioClient;
import io.minio.messages.ErrorResponse;
import io.minio.errors.ErrorResponseException;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class VideoServiceImplTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    // Default answer is false, so every stranger view falls to the PUBLIC-only branch exactly as
    // before FRIENDS existed. The dedicated FRIENDS assertions live in VideoServiceImplCacheTest.
    @MockitoBean
    private com.tiktok.videoservice.client.FriendshipClient friendshipClient;

    @MockitoBean
    private com.tiktok.videoservice.client.BlockClient blockClient;

    // Default answer is an empty set: a private owner's videos stay hidden unless a test says who follows.
    @MockitoBean
    private com.tiktok.videoservice.client.FollowClient followClient;

    // Mocked so no test reaches a real user-service: null settings and no keywords, the fail-open answers.
    @MockitoBean
    private com.tiktok.videoservice.client.UserSettingsClient userSettingsClient;

    // Spied so a test can make owner_privacy answer differently from one read to the next.
    @MockitoSpyBean
    private OwnerPrivacyRepository ownerPrivacyRepository;

    @Autowired
    private VideoService videoService;

    // Spied so a test can count publish's corrective writes.
    @MockitoSpyBean
    private VideoRepository videoRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private OwnerBanRepository ownerBanRepository;

    @Autowired
    private AdminVideoDirectory adminVideoDirectory;

    // Spied, not mocked: presigning stays real (it is local), and publish's existence check is
    // answered here instead of by a MinIO this test does not run. Every upload exists by default.
    @MockitoSpyBean
    private MinioClient minioClient;

    @BeforeEach
    void cleanUp() throws Exception {
        doReturn(null).when(minioClient).statObject(any());
        videoRepository.deleteAll();
        ownerPrivacyRepository.deleteAll();
        ownerBanRepository.deleteAll();
    }

    /**
     * The point of the assertion on fileUrl: it is fed straight back into publish() below, where
     * CreateVideoRequest.rawFileUrl is checked by @ValidMediaUrl against app.media.allowed-buckets.
     * A bucket or scheme change on either side silently makes every upload unpublishable, and this
     * is the only place the two ends meet. The POST form carries the object key as a field, not in
     * the URL, and the client uploads by multipart POST to the bucket URL.
     */
    @Test
    void createUploadUrl_returnsAPostFormAndAStorageUrlPublishAccepts() {
        UploadUrlResponse response = videoService.createUploadUrl(42L, new UploadUrlRequest("video/mp4"));

        assertThat(response.fileUrl()).startsWith("s3://video-media/raw/42/").endsWith(".mp4");
        assertThat(response.uploadUrl()).isEqualTo("http://localhost:9000/video-media");
        assertThat(response.expiresInSeconds()).isPositive();

        String key = response.fileUrl().substring("s3://video-media/".length());
        assertThat(response.formFields())
                .containsEntry("key", key)
                .containsEntry("Content-Type", "video/mp4")
                .containsKey("policy");

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
    /**
     * The key's shape says whose upload it would be, not that one happened. A made-up key used to
     * reach media-worker, which retried the missing file for seconds on its one listener thread.
     */
    @Test
    void publish_aKeyNothingWasUploadedTo_isRejected() throws Exception {
        doThrow(new ErrorResponseException(
                new ErrorResponse("NoSuchKey", "not found", "video-media", "k", "k", "r", "h"), null, null))
                .when(minioClient).statObject(any());

        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Never uploaded", null, "s3://video-media/raw/1/never.mp4", VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(UploadNotFoundException.class);
        assertThat(videoRepository.count()).isZero();
    }

    @Test
    void publish_whileStorageIsDown_isUnavailableRatherThanTheCallersFault() throws Exception {
        doThrow(new java.net.ConnectException("refused")).when(minioClient).statObject(any());

        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Storage down", null, "s3://video-media/raw/1/first.mp4", VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(UploadUrlUnavailableException.class);
        assertThat(videoRepository.count()).isZero();
    }

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
     * media-worker reads the key from the bucket-named segment on, so to the transcode this is
     * {@code raw/2/stolen.mp4} whatever comes before it. The check used to read the segment after
     * the first {@code raw} instead, saw user 1, and let it through.
     */
    @Test
    void publish_aUrlWhoseKeyStartsBehindTheCallersPrefix_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Nested", null, "https://cdn.tiktok-clone.local/raw/1/video-media/raw/2/stolen.mp4",
                VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /** The same shape aimed at a taken-down video's quarantined media, which would go live again. */
    @Test
    void publish_aUrlPointingIntoQuarantine_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Quarantined", null,
                "https://cdn.tiktok-clone.local/raw/1/video-media/quarantine/hls/555/source.mp4",
                VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /**
     * normalize() works on the encoded path, where {@code x%2F..%2F..%2F2%2Fstolen.mp4} is one
     * segment; the dot segments only appear once getPath() decodes it. The key starts with the
     * caller's prefix, so only the exact-shape check stops it.
     */
    @Test
    void publish_aKeyHidingDotSegmentsBehindEncodedSlashes_isRejected() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Encoded", null,
                "https://cdn.tiktok-clone.local/video-media/raw/1/x%2F..%2F..%2F2%2Fstolen.mp4",
                VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(ForeignUploadException.class);
    }

    /**
     * One upload is one video whichever of its URLs comes back. raw_file_idx compares strings, so
     * the stored URL is rebuilt from the key rather than kept as the client spelled it.
     */
    @Test
    void publish_theSameUploadUnderItsCdnUrl_isRejectedAsAlreadyPublished() {
        VideoResponse first = videoService.publish(1L, new CreateVideoRequest(
                "Over https", null, "https://cdn.tiktok-clone.local/video-media/raw/1/twice.mp4",
                VideoVisibility.PUBLIC, List.of()));

        assertThat(videoRepository.findById(first.id()).orElseThrow().getRawFileUrl())
                .isEqualTo("s3://video-media/raw/1/twice.mp4");
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Over s3", null, "s3://video-media/raw/1/twice.mp4", VideoVisibility.PUBLIC, List.of())))
                .isInstanceOf(AlreadyPublishedException.class);
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

    /**
     * The case that was silently broken: uploaders type hashtags into the caption, not into a
     * separate tag field, so a video captioned "#capcut #viral" was reaching Mongo with an empty
     * tag list — no content feature for recommendation-service, and unfindable by tag.
     */
    @Test
    void publish_extractsHashtagsFromTheDescription() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "video_1", "video demo của tôi\n#capcut #viral",
                "s3://video-media/raw/1/captioned.mp4", VideoVisibility.PUBLIC, null));

        assertThat(response.tags()).containsExactly("capcut", "viral");
        assertThat(videoRepository.findById(response.id()).orElseThrow().getTags())
                .containsExactly("capcut", "viral");
    }

    /**
     * A caption hashtag and a typed tag mean the same thing, so the two sources merge instead of
     * one winning — and "#Dance" typed alongside "#dance" captioned is one tag, not two.
     */
    @Test
    void publish_mergesTypedTagsWithCaptionHashtags() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "Both", "món ngon #food #dance", "s3://video-media/raw/1/both.mp4",
                VideoVisibility.PUBLIC, List.of("#Dance")));

        assertThat(response.tags()).containsExactly("dance", "food");
    }

    /**
     * @Size(max = 10) on the request only ever guarded the tags a client sends; a caption can
     * carry any number of hashtags within its 2000 characters, and the list rides on every
     * VideoPublishedEvent.
     */
    @Test
    void publish_capsHashtagsExtractedFromTheDescription() {
        String caption = "#a #b #c #d #e #f #g #h #i #j #k #l";

        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "Many", caption, "s3://video-media/raw/1/many.mp4", VideoVisibility.PUBLIC, null));

        assertThat(response.tags()).containsExactly("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
    }

    @Test
    void publish_withoutTags_storesAnEmptyListNotNull() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "Untagged", null, "s3://video-media/raw/1/untagged.mp4", VideoVisibility.PUBLIC, null));

        assertThat(response.tags()).isEmpty();
    }

    @Test
    void publish_storesTheSanitizedCaption() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "  Vi\u0065\u0302\u0323t\u200B Nam\n ", "dòng 1\n\n\n\ndòng 2",
                "s3://video-media/raw/1/clean.mp4", VideoVisibility.PUBLIC, null));

        assertThat(response.title()).isEqualTo("Việt Nam");
        assertThat(response.description()).isEqualTo("dòng 1\n\ndòng 2");
    }

    /**
     * The worst terms refuse the request before anything exists — no document, no event, no
     * transcode — so the same upload publishes once the caption is fixed.
     */
    @Test
    void publish_aRefusedTitle_storesNothing_andTheUploadCanBePublishedAgain() {
        String upload = "s3://video-media/raw/1/refused.mp4";

        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "địt mẹ mày", null, upload, VideoVisibility.PUBLIC, null)))
                .isInstanceOf(CaptionRejectedException.class)
                .extracting("code").isEqualTo("VIDEO_TITLE_REJECTED");
        assertThat(videoRepository.count()).isZero();

        assertThat(videoService.publish(1L, new CreateVideoRequest(
                "Video mới", null, upload, VideoVisibility.PUBLIC, null)).status())
                .isEqualTo(VideoStatus.PROCESSING);
    }

    @Test
    void publish_aRefusedDescription_namesTheDescription() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Bình thường", "đồ con đĩ", "s3://video-media/raw/1/desc.mp4", VideoVisibility.PUBLIC, null)))
                .extracting("code").isEqualTo("VIDEO_DESCRIPTION_REJECTED");
    }

    /** Tags are read as one text, so a phrase spelled across them is still the phrase. */
    @Test
    void publish_aRefusedPhraseSpreadAcrossTags_namesTheTags() {
        assertThatThrownBy(() -> videoService.publish(1L, new CreateVideoRequest(
                "Bình thường", null, "s3://video-media/raw/1/tags.mp4", VideoVisibility.PUBLIC,
                List.of("địt", "mẹ", "mày"))))
                .extracting("code").isEqualTo("VIDEO_TAGS_REJECTED");
    }

    /**
     * Held, not refused: the video is created and goes down the pipeline like any other. The
     * verdict sits on the document for the console and rides on the publication for media-worker.
     */
    @Test
    void publish_aFlaggedCaption_isRecordedForReview_andNeverShownPublicly() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "Săn sale", "mua tại abc.xyz hoặc gọi 0912345678", "s3://video-media/raw/1/sale.mp4",
                VideoVisibility.PUBLIC, null));

        VideoTextModeration recorded = videoRepository.findById(response.id()).orElseThrow().getTextModeration();
        assertThat(recorded.getVerdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(recorded.getFlags())
                .containsExactly("DESCRIPTION:SPAM:link:abc.xyz", "DESCRIPTION:PERSONAL_INFO:phone");
        assertThat(recorded.isEnforced()).isTrue();
        assertThat(recorded.getVersion()).startsWith("rules-");
        assertThat(response.status()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(response.textModeration()).isNull();
        assertThat(adminVideoDirectory.getById(response.id()).textModeration().verdict())
                .isEqualTo(ModerationVerdict.REVIEW);
    }

    /** Creators link their other profiles; those domains need no review in a caption. */
    @Test
    void publish_aCaptionLinkingTheCreatorsOwnProfile_isApproved() {
        VideoResponse response = videoService.publish(1L, new CreateVideoRequest(
                "Nấu phở", "Công thức đầy đủ trên youtube.com/@bepnha #phở", "s3://video-media/raw/1/pho.mp4",
                VideoVisibility.PUBLIC, null));

        assertThat(videoRepository.findById(response.id()).orElseThrow().getTextModeration().getVerdict())
                .isEqualTo(ModerationVerdict.APPROVED);
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

    /**
     * The ranking arrives as the id order and has to survive the round trip. Mongo answers an
     * $in query in whatever order it likes, so a straight pass-through of the query result would
     * hand back a feed that looks ranked and is not — a failure with no visible symptom.
     */
    @Test
    void getByIds_answersInTheOrderAsked() {
        VideoResponse first = publishedPublic(1L, "s3://video-media/raw/1/b1.mp4");
        VideoResponse second = publishedPublic(1L, "s3://video-media/raw/1/b2.mp4");
        VideoResponse third = publishedPublic(1L, "s3://video-media/raw/1/b3.mp4");

        List<VideoResponse> hydrated = videoService.getByIds(
                null, List.of(third.id(), first.id(), second.id()), null);

        assertThat(hydrated).extracting(VideoResponse::id)
                .containsExactly(third.id(), first.id(), second.id());
    }

    /**
     * A feed can name a video that was deleted or hidden since it was ranked — the deletion event
     * takes seconds to propagate — so the caller gets a shorter list, not an error.
     */
    @Test
    void getByIds_dropsWhatTheCallerMayNotSee() {
        VideoResponse visible = publishedPublic(1L, "s3://video-media/raw/1/b4.mp4");
        VideoResponse stillProcessing = videoService.publish(1L,
                new CreateVideoRequest("Processing", null, "s3://video-media/raw/1/b5.mp4", VideoVisibility.PUBLIC, List.of()));
        VideoResponse someoneElsesPrivate = videoService.publish(2L,
                new CreateVideoRequest("Private", null, "s3://video-media/raw/2/b6.mp4", VideoVisibility.PRIVATE, List.of()));
        markPublished(someoneElsesPrivate.id());
        VideoResponse deleted = publishedPublic(1L, "s3://video-media/raw/1/b7.mp4");
        videoService.delete(1L, deleted.id());

        List<VideoResponse> hydrated = videoService.getByIds(1L, List.of(
                stillProcessing.id(), visible.id(), someoneElsesPrivate.id(), deleted.id(), "no-such-id"), null);

        // stillProcessing survives: it is the caller's own, and an owner sees their whole shelf.
        assertThat(hydrated).extracting(VideoResponse::id)
                .containsExactly(stillProcessing.id(), visible.id());
    }

    private VideoResponse publishedAs(Long userId, String title) {
        VideoResponse video = publishAs(userId, title, VideoVisibility.PUBLIC);
        markPublished(video.id());
        return video;
    }

    private void filtersFromForYou(Long viewer, String keyword) {
        when(userSettingsClient.feedKeywords(viewer)).thenReturn(new FeedKeywords(List.of(keyword), List.of(), List.of()));
    }

    /** What the For You tab hydrates its ranking through. Without the feed it is not a feed — a share link still opens. */
    @Test
    void getByIds_forYouGoesThroughTheFilter() {
        VideoResponse cat = publishedAs(1L, "my cat");
        VideoResponse dog = publishedAs(1L, "my dog");
        filtersFromForYou(9L, "cat");

        assertThat(videoService.getByIds(9L, List.of(cat.id(), dog.id()), FeedKind.FOR_YOU))
                .extracting(VideoResponse::id).containsExactly(dog.id());
        assertThat(videoService.getByIds(9L, List.of(cat.id(), dog.id()), null))
                .extracting(VideoResponse::id).containsExactly(cat.id(), dog.id());
    }

    /** For You falls back to the chronological feed when ranking is down, and the keywords still hold there. */
    @Test
    void getFeed_forYouFallbackGoesThroughTheFilter() {
        VideoResponse cat = publishedAs(1L, "my cat");
        VideoResponse dog = publishedAs(1L, "my dog");
        filtersFromForYou(9L, "cat");

        assertThat(videoService.getFeed(9L, null, 10, FeedKind.FOR_YOU).items())
                .extracting(VideoResponse::id).containsExactly(dog.id());
        assertThat(videoService.getFeed(null, null, 10, FeedKind.FOR_YOU).items())
                .extracting(VideoResponse::id).containsExactly(dog.id(), cat.id());
    }

    /**
     * A page the filter shortened keeps the cursor of the page as read: moving it back to the last
     * survivor would serve the dropped videos' successors twice, moving it on is what already happens.
     */
    @Test
    void followingFeed_keepsTheCursorWhenThePageShrinks() {
        VideoResponse older = publishedAs(1L, "my cat");
        VideoResponse middle = publishedAs(1L, "my dog");
        VideoResponse newest = publishedAs(1L, "another cat");
        when(userSettingsClient.feedKeywords(9L)).thenReturn(new FeedKeywords(List.of(), List.of("cat"), List.of()));

        CursorPage<VideoResponse> first = videoService.getFollowingFeed(9L, List.of(1L), null, 2, FeedKind.FOLLOWING);
        CursorPage<VideoResponse> unfiltered = videoService.getFollowingFeed(9L, List.of(1L), null, 2, FeedKind.FRIENDS);

        assertThat(first.items()).extracting(VideoResponse::id).containsExactly(middle.id());
        assertThat(unfiltered.items()).extracting(VideoResponse::id).containsExactly(newest.id(), middle.id());
        assertThat(first.nextCursor()).isNotNull().isEqualTo(unfiltered.nextCursor());
        CursorPage<VideoResponse> second = videoService.getFollowingFeed(9L, List.of(1L), first.nextCursor(), 2, FeedKind.FOLLOWING);
        assertThat(second.items()).isEmpty();
        assertThat(videoService.getFollowingFeed(9L, List.of(1L), first.nextCursor(), 2, FeedKind.FRIENDS).items())
                .extracting(VideoResponse::id).containsExactly(older.id());
    }

    private VideoResponse publishedPublic(Long userId, String rawFileUrl) {
        VideoResponse video = videoService.publish(userId,
                new CreateVideoRequest("Batch", null, rawFileUrl, VideoVisibility.PUBLIC, List.of()));
        markPublished(video.id());
        return video;
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

        CursorPage<VideoResponse> feed = videoService.getFeed(null, null, 10, null);

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
            CursorPage<VideoResponse> page = videoService.getFeed(null, cursor, 7, null);
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

        assertThat(videoService.getFeed(null, null, 1000, null).items()).hasSize(50);
    }

    @Test
    void getFollowingFeed_onlyDrawsFromTheFollowedAuthors() {
        VideoResponse followed = publishAs(1L, "Followed", VideoVisibility.PUBLIC);
        markPublished(followed.id());
        VideoResponse alsoFollowed = publishAs(2L, "Also followed", VideoVisibility.PUBLIC);
        markPublished(alsoFollowed.id());
        VideoResponse stranger = publishAs(3L, "Stranger", VideoVisibility.PUBLIC);
        markPublished(stranger.id());

        CursorPage<VideoResponse> feed = videoService.getFollowingFeed(null, List.of(1L, 2L), null, 10, null);

        // Newest first, so the seeding order reversed.
        assertThat(feed.items()).extracting(VideoResponse::id)
                .containsExactly(alsoFollowed.id(), followed.id());
    }

    /**
     * The Following feed is the public feed narrowed by author, not a way around the filter it
     * applies: following someone does not put their PRIVATE or still-PROCESSING uploads, or what
     * moderation took down, on your feed.
     */
    @Test
    void getFollowingFeed_appliesTheSameVisibilityRuleAsThePublicFeed() {
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());
        VideoResponse processing = publishAs(1L, "Processing", VideoVisibility.PUBLIC);
        VideoResponse privatePublished = publishAs(1L, "Private", VideoVisibility.PRIVATE);
        markPublished(privatePublished.id());
        VideoResponse takenDown = publishAs(1L, "Taken down", VideoVisibility.PUBLIC);
        markPublished(takenDown.id());
        markTakenDown(takenDown.id());

        CursorPage<VideoResponse> feed = videoService.getFollowingFeed(null, List.of(1L), null, 10, null);

        assertThat(feed.items()).extracting(VideoResponse::id).containsExactly(published.id());
        assertThat(feed.items()).extracting(VideoResponse::id)
                .doesNotContain(processing.id(), privatePublished.id(), takenDown.id());
    }

    /** Following nobody is an empty tab — never a silent fallback to the whole public feed. */
    @Test
    void getFollowingFeed_noFollowedAuthors_isEmptyRatherThanThePublicFeed() {
        VideoResponse stranger = publishAs(3L, "Stranger", VideoVisibility.PUBLIC);
        markPublished(stranger.id());

        assertThat(videoService.getFollowingFeed(null, List.of(), null, 10, null).items()).isEmpty();
        assertThat(videoService.getFollowingFeed(null, null, null, 10, null).items()).isEmpty();
    }

    /** Same keyset property {@link #getFeed_pagingByCursor_visitsEveryVideoExactlyOnce} asserts. */
    @Test
    void getFollowingFeed_pagingByCursor_visitsEveryVideoExactlyOnce() {
        List<String> followedIds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            // Alternating authors, so a page boundary routinely falls between two of them.
            VideoResponse video = publishAs(i % 2 == 0 ? 1L : 2L, "video-" + i, VideoVisibility.PUBLIC);
            markPublished(video.id());
            followedIds.add(video.id());
        }
        VideoResponse stranger = publishAs(3L, "Stranger", VideoVisibility.PUBLIC);
        markPublished(stranger.id());

        List<String> walked = new ArrayList<>();
        String cursor = null;
        do {
            CursorPage<VideoResponse> page = videoService.getFollowingFeed(null, List.of(1L, 2L), cursor, 7, null);
            walked.addAll(page.items().stream().map(VideoResponse::id).toList());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(walked).containsExactlyElementsOf(followedIds.reversed());
        assertThat(walked).doesNotContain(stranger.id());
    }

    @Test
    void getFollowingFeed_tooManyAuthors_isRefusedRatherThanTruncated() {
        List<Long> tooMany = LongStream.rangeClosed(1, 501).boxed().toList();

        assertThatThrownBy(() -> videoService.getFollowingFeed(null, tooMany, null, 10, null))
                .isInstanceOf(TooManyFollowedUsersException.class);
    }

    @Test
    void getFeed_unusableCursor_isRejected() {
        assertThatThrownBy(() -> videoService.getFeed(null, "not-a-cursor", 10, null))
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

    /**
     * The whole path a video takes to the feed: transcoded, then cleared by moderation. Both
     * steps, because a transcode alone now stops at PENDING_MODERATION and nothing there is
     * visible to anyone.
     */
    private void markPublished(String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId).orElseThrow();
        video.markTranscoded(null, null, null, null, null, null);
        video.applyModeration(VideoModeration.builder()
                .verdict(ModerationVerdict.APPROVED)
                .totalFrames(10)
                .checkedAt(java.time.Instant.now())
                .build());
        videoRepository.save(video);
    }

    private void markTakenDown(String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId).orElseThrow();
        PipelineState read = PipelineState.of(video);
        video.markTakenDown("policy violation");
        videoRepository.updateStatus(video, read);
    }

    /** What UserPrivacyEventConsumer records; publish reads it to stamp the video. */
    private void makePrivate(long userId) {
        ownerPrivacyRepository.save(new OwnerPrivacy(userId, true, java.time.Instant.now()));
    }

    /** The public feed is for videos anyone may see, so a private owner's never appear on it. */
    @Test
    void getFeed_leavesOutPrivateOwners() {
        makePrivate(1L);
        VideoResponse privateOwners = publishAs(1L, "Private owner", VideoVisibility.PUBLIC);
        markPublished(privateOwners.id());
        VideoResponse publicOwners = publishAs(2L, "Public owner", VideoVisibility.PUBLIC);
        markPublished(publicOwners.id());

        assertThat(videoService.getFeed(null, null, 10, null).items()).extracting(VideoResponse::id)
                .containsExactly(publicOwners.id());
    }

    /**
     * The author ids come from the client, so naming a private account must not be a way into its
     * videos. Dropped before the query, so the page and its cursor stay whole.
     */
    @Test
    void getFollowingFeed_dropsPrivateOwnersTheCallerDoesNotFollow() {
        makePrivate(1L);
        makePrivate(2L);
        VideoResponse followed = publishAs(1L, "Followed", VideoVisibility.PUBLIC);
        markPublished(followed.id());
        VideoResponse notFollowed = publishAs(2L, "Not followed", VideoVisibility.PUBLIC);
        markPublished(notFollowed.id());
        VideoResponse publicOwners = publishAs(3L, "Public", VideoVisibility.PUBLIC);
        markPublished(publicOwners.id());
        when(followClient.followedAmong(eq(9L), anyCollection())).thenReturn(Set.of(1L));

        CursorPage<VideoResponse> feed = videoService.getFollowingFeed(9L, List.of(1L, 2L, 3L), null, 10, null);

        assertThat(feed.items()).extracting(VideoResponse::id)
                .containsExactly(publicOwners.id(), followed.id());
        verify(followClient).followedAmong(9L, Set.of(1L, 2L));
    }

    @Test
    void getFollowingFeed_anonymous_losesEveryPrivateOwner() {
        makePrivate(1L);
        VideoResponse privateOwners = publishAs(1L, "Private", VideoVisibility.PUBLIC);
        markPublished(privateOwners.id());
        VideoResponse publicOwners = publishAs(2L, "Public", VideoVisibility.PUBLIC);
        markPublished(publicOwners.id());

        assertThat(videoService.getFollowingFeed(null, List.of(1L, 2L), null, 10, null).items())
                .extracting(VideoResponse::id)
                .containsExactly(publicOwners.id());
    }

    /** Nobody follows themselves: a private owner naming their own id still sees their own videos. */
    @Test
    void getFollowingFeed_aPrivateOwnerStillSeesTheirOwn() {
        makePrivate(1L);
        VideoResponse own = publishAs(1L, "Own", VideoVisibility.PUBLIC);
        markPublished(own.id());

        assertThat(videoService.getFollowingFeed(1L, List.of(1L), null, 10, null).items())
                .extracting(VideoResponse::id)
                .containsExactly(own.id());
        verifyNoInteractions(followClient);
    }

    /** A 403 rather than an empty grid, so the client can say the account is private. */
    @Test
    void listByUser_privateOwner_refusesAStrangerAndServesAFollower() {
        makePrivate(1L);
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());
        when(followClient.followedAmong(2L, List.of(1L))).thenReturn(Set.of(1L));

        assertThat(videoService.listByUser(2L, 1L, PageRequest.of(0, 10)).getContent())
                .extracting(VideoResponse::id)
                .containsExactly(published.id());
        assertThatThrownBy(() -> videoService.listByUser(3L, 1L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
        assertThatThrownBy(() -> videoService.listByUser(null, 1L, PageRequest.of(0, 10)))
                .isInstanceOf(PrivateAccountException.class);
        assertThat(videoService.listByUser(1L, 1L, PageRequest.of(0, 10)).getContent()).hasSize(1);
    }

    /** Counters stay public, as on TikTok: a private account still shows how many videos it has. */
    @Test
    void getUserStats_ofAPrivateOwner_staysPublic() {
        makePrivate(1L);
        VideoResponse published = publishAs(1L, "Published", VideoVisibility.PUBLIC);
        markPublished(published.id());

        assertThat(videoService.getUserStats(null, 1L).videoCount()).isEqualTo(1);
    }

    @Test
    void publish_stampsTheOwnersPrivacy() {
        makePrivate(1L);

        VideoResponse published = publishAs(1L, "Stamped", VideoVisibility.PUBLIC);

        Video stored = videoRepository.findById(published.id()).orElseThrow();
        assertThat(published.ownerPrivate()).isTrue();
        assertThat(stored.isOwnerPrivate()).isTrue();
        // Both reads agreed, so there is nothing extra to announce.
        assertThat(stored.getVisibilityEventPendingAt()).isNull();
    }

    /**
     * The owner goes private between the first read and the insert, and the consumer's fan-out ran
     * before the insert — so it missed this video. Only the second read can catch it.
     */
    @Test
    void publish_whenTheOwnerGoesPrivateMidway_catchesItOnTheSecondRead() {
        doReturn(false).doReturn(true).when(ownerPrivacyRepository).existsByUserIdAndPrivateAccountTrue(1L);

        VideoResponse published = publishAs(1L, "Midway", VideoVisibility.PUBLIC);

        Video stored = videoRepository.findById(published.id()).orElseThrow();
        assertThat(published.ownerPrivate()).isTrue();
        assertThat(stored.isOwnerPrivate()).isTrue();
        assertThat(stored.getVisibilityEventPendingAt()).isNotNull();
    }

    /**
     * The owner goes private and straight back while the video is being published, so the
     * corrective write carries a value that is already stale when it lands. Only a read after it
     * can tell.
     */
    @Test
    void publish_whenTheOwnerSwitchesBackMidway_readsAgainAfterEachCorrection() {
        doReturn(false).doReturn(true).doReturn(false)
                .when(ownerPrivacyRepository).existsByUserIdAndPrivateAccountTrue(1L);

        VideoResponse published = publishAs(1L, "Switched back", VideoVisibility.PUBLIC);

        assertThat(published.ownerPrivate()).isFalse();
        assertThat(videoRepository.findById(published.id()).orElseThrow().isOwnerPrivate()).isFalse();
        verify(videoRepository, times(2)).updateOwnerPrivacy(any(Video.class));
    }

    /** What the ban consumer records; every viewer-facing read checks it. */
    private void ban(long userId, Instant until) {
        ownerBanRepository.save(new OwnerBan(userId, until, Instant.now()));
    }

    /**
     * A ban ended the account's sessions and nothing else: its videos went on playing on the feed,
     * on its profile and through every link anyone had kept. They leave every path a viewer reads.
     */
    @Test
    void aBannedOwnersVideos_leaveEveryViewerFacingPath() {
        VideoResponse banned = publishAs(1L, "Banned", VideoVisibility.PUBLIC);
        markPublished(banned.id());
        VideoResponse other = publishAs(2L, "Other", VideoVisibility.PUBLIC);
        markPublished(other.id());
        ban(1L, OwnerBan.PERMANENT);

        assertThat(videoService.getFeed(null, null, 10, null).items()).extracting(VideoResponse::id)
                .containsExactly(other.id());
        assertThat(videoService.getFollowingFeed(9L, List.of(1L, 2L), null, 10, null).items())
                .extracting(VideoResponse::id)
                .containsExactly(other.id());
        assertThat(videoService.getByIds(9L, List.of(banned.id(), other.id()), null)).extracting(VideoResponse::id)
                .containsExactly(other.id());
        assertThatThrownBy(() -> videoService.getById(9L, banned.id())).isInstanceOf(VideoNotFoundException.class);
        assertThat(videoService.listByUser(9L, 1L, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(videoService.getUserStats(9L, 1L).videoCount()).isZero();
    }

    /** An owner who closed their account is hidden on the same paths as a banned one. */
    @Test
    void aClosedAccountsVideos_leaveEveryViewerFacingPath() {
        VideoResponse closed = publishAs(1L, "Closed", VideoVisibility.PUBLIC);
        markPublished(closed.id());
        VideoResponse other = publishAs(2L, "Other", VideoVisibility.PUBLIC);
        markPublished(other.id());
        ownerBanRepository.save(new OwnerBan(1L, null, null, Instant.now(), Instant.now()));

        assertThat(videoService.getFeed(null, null, 10, null).items()).extracting(VideoResponse::id)
                .containsExactly(other.id());
        assertThat(videoService.getFollowingFeed(9L, List.of(1L, 2L), null, 10, null).items())
                .extracting(VideoResponse::id)
                .containsExactly(other.id());
        assertThat(videoService.getByIds(9L, List.of(closed.id(), other.id()), null)).extracting(VideoResponse::id)
                .containsExactly(other.id());
        assertThatThrownBy(() -> videoService.getById(9L, closed.id())).isInstanceOf(VideoNotFoundException.class);
        assertThat(videoService.listByUser(9L, 1L, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(videoService.getUserStats(9L, 1L).videoCount()).isZero();
    }

    /** Nothing announces the end of a temporary ban — auth-service lifts it quietly — so it ends here by itself. */
    @Test
    void aBanThatHasRunOut_hidesNothing() {
        VideoResponse video = publishAs(1L, "Served again", VideoVisibility.PUBLIC);
        markPublished(video.id());
        ban(1L, Instant.now().minusSeconds(60));

        assertThat(videoService.getFeed(null, null, 10, null).items()).extracting(VideoResponse::id)
                .containsExactly(video.id());
        assertThat(videoService.getById(9L, video.id()).id()).isEqualTo(video.id());
        assertThat(videoService.listByUser(9L, 1L, PageRequest.of(0, 10)).getContent()).hasSize(1);
    }

    /** Videos stored before ownerPrivate existed have no such field, and their owners are public. */
    @Test
    void getFeed_keepsAVideoStoredWithoutOwnerPrivate() {
        VideoResponse published = publishAs(1L, "Stored before", VideoVisibility.PUBLIC);
        markPublished(published.id());
        mongoTemplate.updateFirst(Query.query(where("_id").is(published.id())),
                new Update().unset("ownerPrivate"), Video.class);
        assertThat(mongoTemplate.findById(published.id(), Document.class, "videos"))
                .doesNotContainKey("ownerPrivate");

        assertThat(videoService.getFeed(null, null, 10, null).items()).extracting(VideoResponse::id)
                .containsExactly(published.id());
    }
}
