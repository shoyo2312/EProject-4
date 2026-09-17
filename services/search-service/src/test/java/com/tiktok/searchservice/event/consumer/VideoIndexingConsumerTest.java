package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.event.video.VideoVisibilityChangedEvent;
import com.tiktok.searchservice.document.ProcessedEventDocument;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.data.domain.PageRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class VideoIndexingConsumerTest {

    @Container
    @ServiceConnection
    static ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.14.0"))
            .withEnv("xpack.security.enabled", "false");

    @Autowired
    private VideoEventConsumer videoEventConsumer;

    @Autowired
    private VideoTranscodedEventConsumer videoTranscodedEventConsumer;

    @Autowired
    private VideoModerationEventConsumer videoModerationEventConsumer;

    @Autowired
    private CommentEventConsumer commentEventConsumer;

    @Autowired
    private AdminModerationEventConsumer adminModerationEventConsumer;

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private SearchService searchService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        deleteAll(VideoDocument.class);
        deleteAll(ProcessedEventDocument.class);
    }

    @Test
    void onMessage_indexesVideoThenAppliesTranscodingThenTheVerdict() throws Exception {
        publish("v1", "My first video", "caption #dance", List.of("dance"));

        VideoDocument indexed = video("v1").orElseThrow();
        assertThat(indexed.getTitle()).isEqualTo("My first video");
        assertThat(indexed.getDescription()).isEqualTo("caption #dance");
        assertThat(indexed.getTags()).containsExactly("dance");
        assertThat(indexed.getStatus()).isEqualTo("PROCESSING");

        transcode(VideoTranscodedEvent.success("v1", "http://minio/thumb.jpg", null, "http://minio/master.m3u8", 42));

        // Playable, not published: the media is on the document but the video has not been
        // screened, and PENDING_MODERATION is not a status the search query matches.
        VideoDocument transcoded = video("v1").orElseThrow();
        assertThat(transcoded.getStatus()).isEqualTo("PENDING_MODERATION");
        assertThat(transcoded.getThumbnailUrl()).isEqualTo("http://minio/thumb.jpg");
        assertThat(transcoded.getDurationSeconds()).isEqualTo(42);

        moderate("v1", ModerationVerdict.APPROVED);

        assertThat(video("v1").orElseThrow().getStatus()).isEqualTo("PUBLISHED");
    }

    /**
     * The failure this whole listener exists for: a transcode success used to be indexed as
     * PUBLISHED, so every upload was findable before anything screened it — and a video the
     * classifier went on to reject stayed findable, because nothing else ever revisited its
     * status.
     */
    @Test
    void searchVideos_excludesAVideoUntilItHasBeenApproved() throws Exception {
        publish("v11", "unscreened clip", null, List.of());
        transcode(VideoTranscodedEvent.success("v11", "http://minio/t.jpg", null, "http://minio/m.m3u8", 4));

        elasticsearchOperations.indexOps(VideoDocument.class).refresh();
        assertThat(searchService.searchVideos("unscreened", null, PageRequest.of(0, 10))).isEmpty();

        moderate("v11", ModerationVerdict.APPROVED);

        elasticsearchOperations.indexOps(VideoDocument.class).refresh();
        assertThat(searchService.searchVideos("unscreened", null, PageRequest.of(0, 10)))
                .extracting(response -> response.id())
                .containsExactly("v11");
    }

    /** A rejected video is never searchable, and no later event is coming to remove it. */
    @Test
    void searchVideos_excludesARejectedVideo() throws Exception {
        publish("v12", "rejected clip", null, List.of());
        transcode(VideoTranscodedEvent.success("v12", "http://minio/t.jpg", null, "http://minio/m.m3u8", 4));
        moderate("v12", ModerationVerdict.REJECTED);

        elasticsearchOperations.indexOps(VideoDocument.class).refresh();
        assertThat(video("v12").orElseThrow().getStatus()).isEqualTo("REJECTED");
        assertThat(searchService.searchVideos("rejected", null, PageRequest.of(0, 10))).isEmpty();
    }

    /**
     * A moderator acting on a video whose verdict is still in flight is routinely overtaken by
     * it. The outcome goes to pendingStatus for the restore to read; it must not put the video
     * back into search results.
     */
    @Test
    void onMessage_verdictAfterTakedown_doesNotUndoTheTakedown() throws Exception {
        publish("v13", "taken down early", null, List.of());
        transcode(VideoTranscodedEvent.success("v13", "http://minio/t.jpg", null, "http://minio/m.m3u8", 4));

        VideoTakenDownEvent takenDown = VideoTakenDownEvent.of("v13", 99L, "spam");
        adminModerationEventConsumer.onMessage(
                objectMapper.writeValueAsString(takenDown), header("VideoTakenDownEvent"));

        moderate("v13", ModerationVerdict.APPROVED);

        assertThat(video("v13").orElseThrow().getStatus()).isEqualTo("TAKEN_DOWN");

        VideoRestoredEvent restored = VideoRestoredEvent.of("v13", 99L, "appeal upheld");
        adminModerationEventConsumer.onMessage(
                objectMapper.writeValueAsString(restored), header("VideoRestoredEvent"));

        assertThat(video("v13").orElseThrow().getStatus()).isEqualTo("PUBLISHED");
    }

    /**
     * The two events travel on different topics, so nothing orders them — and a cold start
     * replaying both from {@code earliest} makes this the normal case, not the rare one. The
     * transcode result used to be dropped when it arrived first, leaving the video stuck at
     * PROCESSING and invisible to search for good, with its eventId already marked processed so
     * no redelivery could repair it.
     */
    @Test
    void onMessage_transcodeBeforePublication_stillKeepsTheOutcome() throws Exception {
        transcode(VideoTranscodedEvent.success("v5", "http://minio/thumb.jpg", null, "http://minio/master.m3u8", 7));

        // Not searchable yet: the stub has no content to show, so it must not carry a status.
        assertThat(video("v5").orElseThrow().getStatus()).isNull();

        publish("v5", "late title", null, List.of());

        VideoDocument document = video("v5").orElseThrow();
        assertThat(document.getStatus()).isEqualTo("PENDING_MODERATION");
        assertThat(document.getTitle()).isEqualTo("late title");
        assertThat(document.getThumbnailUrl()).isEqualTo("http://minio/thumb.jpg");
    }

    /** A publication landing after a transcode must not roll the status back to PROCESSING. */
    @Test
    void onMessage_publicationReplay_doesNotOverwriteTranscodedStatus() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("v6", 1L, "title", null, "s3://raw/6.mp4", "PUBLIC", List.of());
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));
        transcode(VideoTranscodedEvent.success("v6", "http://minio/t.jpg", null, "http://minio/m.m3u8", 9));

        // Same videoId, a fresh event: the derived eventId is stable, so this is a genuine replay.
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        assertThat(video("v6").orElseThrow().getStatus()).isEqualTo("PENDING_MODERATION");
    }

    @Test
    void onMessage_replay_isNoOp() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("v2", 1L, "title", null, "s3://raw/2.mp4", "PUBLIC", List.of());
        String payload = objectMapper.writeValueAsString(published);

        videoEventConsumer.onMessage(payload, header("VideoPublishedEvent"));
        videoEventConsumer.onMessage(payload, header("VideoPublishedEvent"));

        assertThat(count(ProcessedEventDocument.class)).isEqualTo(1);
    }

    /**
     * The user-visible half of the deletion event: without it a removed video keeps coming back
     * in search results, because nothing in this service ever hears that it is gone.
     */
    @Test
    void onMessage_deletion_dropsTheDocumentFromTheIndex() throws Exception {
        publish("v3", "title", null, List.of());
        assertThat(video("v3")).isPresent();

        VideoDeletedEvent deleted = VideoDeletedEvent.of("v3", 1L, "s3://raw/3.mp4");
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(deleted), header("VideoDeletedEvent"));

        assertThat(video("v3")).isEmpty();
    }

    /**
     * The publication and the deletion of one video must not share a processed-events id, or
     * whichever arrives second is mistaken for a replay of the first and dropped.
     */
    @Test
    void onMessage_deletion_isNotMistakenForAReplayOfThePublication() {
        VideoPublishedEvent published = VideoPublishedEvent.of("v4", 1L, "title", null, "s3://raw/4.mp4", "PUBLIC", List.of());
        VideoDeletedEvent deleted = VideoDeletedEvent.of("v4", 1L, "s3://raw/4.mp4");

        assertThat(deleted.eventId()).isNotEqualTo(published.eventId());
    }

    /**
     * interaction.comment-events carries both shapes. Parsing every record as a creation — which
     * Jackson does without complaint, filling the missing fields with null — made deleting a
     * comment increment the count.
     */
    @Test
    void onMessage_commentDeleted_decrementsRatherThanIncrements() throws Exception {
        // A numeric id, because interaction events carry videoId as a Long — video-service ids
        // are String.valueOf(snowflake), so the two meet as the same digits.
        publish("7", "title", null, List.of());

        comment("CommentCreatedEvent", CommentCreatedEvent.of(1L, 7L, 2L, "nice"));
        comment("CommentCreatedEvent", CommentCreatedEvent.of(2L, 7L, 2L, "also nice"));
        assertThat(video("7").orElseThrow().getCommentCount()).isEqualTo(2);

        comment("CommentDeletedEvent", CommentDeletedEvent.of(2L, 7L, 2L));

        assertThat(video("7").orElseThrow().getCommentCount()).isEqualTo(1);
    }

    /** A moderated video must leave the index's visible set, and come back as it was. */
    @Test
    void onMessage_takedownThenRestore_returnsTheTranscodedStatus() throws Exception {
        publish("v8", "title", null, List.of());
        transcode(VideoTranscodedEvent.success("v8", "http://minio/t.jpg", null, "http://minio/m.m3u8", 5));
        moderate("v8", ModerationVerdict.APPROVED);

        VideoTakenDownEvent takenDown = VideoTakenDownEvent.of("v8", 99L, "spam");
        adminModerationEventConsumer.onMessage(
                objectMapper.writeValueAsString(takenDown), header("VideoTakenDownEvent"));
        assertThat(video("v8").orElseThrow().getStatus()).isEqualTo("TAKEN_DOWN");

        VideoRestoredEvent restored = VideoRestoredEvent.of("v8", 99L, "appeal upheld");
        adminModerationEventConsumer.onMessage(
                objectMapper.writeValueAsString(restored), header("VideoRestoredEvent"));

        assertThat(video("v8").orElseThrow().getStatus()).isEqualTo("PUBLISHED");
    }

    /**
     * A private video is PUBLISHED like any other — moderation is what sets that, and it does not
     * look at visibility. Search is a read path of its own, with no viewer identity and no call
     * into video-service, so filtering on status alone handed the title, description and thumbnail
     * of every private video to anyone who searched for it.
     */
    @Test
    void searchVideos_excludesPrivateVideosUntilTheirOwnerMakesThemPublic() throws Exception {
        publish("v9", "diary entry", "not for you", "PRIVATE", List.of());
        transcode(VideoTranscodedEvent.success("v9", "http://minio/t.jpg", null, "http://minio/m.m3u8", 3));
        moderate("v9", ModerationVerdict.APPROVED);
        assertThat(video("v9").orElseThrow().getStatus()).isEqualTo("PUBLISHED");

        elasticsearchOperations.indexOps(VideoDocument.class).refresh();
        assertThat(searchService.searchVideos("diary", null, PageRequest.of(0, 10))).isEmpty();

        VideoVisibilityChangedEvent madePublic =
                VideoVisibilityChangedEvent.of("v9", 1L, "PUBLIC", Instant.now());
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(madePublic),
                header("VideoVisibilityChangedEvent"));

        elasticsearchOperations.indexOps(VideoDocument.class).refresh();
        assertThat(searchService.searchVideos("diary", null, PageRequest.of(0, 10)))
                .extracting(response -> response.id())
                .containsExactly("v9");
    }

    /** And the way back: a public video its owner hides leaves the results it was already in. */
    @Test
    void searchVideos_dropsAVideoItsOwnerMakesPrivate() throws Exception {
        publish("v10", "beach trip", null, List.of());
        transcode(VideoTranscodedEvent.success("v10", "http://minio/t.jpg", null, "http://minio/m.m3u8", 3));
        moderate("v10", ModerationVerdict.APPROVED);
        elasticsearchOperations.indexOps(VideoDocument.class).refresh();
        assertThat(searchService.searchVideos("beach", null, PageRequest.of(0, 10))).hasSize(1);

        VideoVisibilityChangedEvent madePrivate =
                VideoVisibilityChangedEvent.of("v10", 1L, "PRIVATE", Instant.now());
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(madePrivate),
                header("VideoVisibilityChangedEvent"));

        elasticsearchOperations.indexOps(VideoDocument.class).refresh();
        assertThat(searchService.searchVideos("beach", null, PageRequest.of(0, 10))).isEmpty();
    }

    private void publish(String videoId, String title, String description, List<String> tags) throws Exception {
        publish(videoId, title, description, "PUBLIC", tags);
    }

    private void publish(String videoId, String title, String description, String visibility,
                         List<String> tags) throws Exception {
        VideoPublishedEvent event = VideoPublishedEvent.of(
                videoId, 1L, title, description, "s3://raw/" + videoId + ".mp4", visibility, tags);
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(event), header("VideoPublishedEvent"));
    }

    private void transcode(VideoTranscodedEvent event) throws Exception {
        videoTranscodedEventConsumer.onMessage(objectMapper.writeValueAsString(event));
    }

    private void moderate(String videoId, ModerationVerdict verdict) throws Exception {
        VideoModerationCompletedEvent event = VideoModerationCompletedEvent.of(
                videoId, verdict, "nsfw", 0.1, 0, 10, 900L, "model", "v1", null);
        videoModerationEventConsumer.onMessage(objectMapper.writeValueAsString(event));
    }

    private void comment(String eventType, Object event) throws Exception {
        commentEventConsumer.onMessage(objectMapper.writeValueAsString(event), header(eventType));
    }

    private Optional<VideoDocument> video(String id) {
        return Optional.ofNullable(elasticsearchOperations.get(id, VideoDocument.class));
    }

    private long count(Class<?> type) {
        elasticsearchOperations.indexOps(type).refresh();
        return elasticsearchOperations.count(Query.findAll(), type);
    }

    private void deleteAll(Class<?> type) {
        var indexOps = elasticsearchOperations.indexOps(type);
        if (indexOps.exists()) {
            indexOps.delete();
        }
        indexOps.createWithMapping();
        indexOps.refresh();
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }
}
