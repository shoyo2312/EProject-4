package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What the cache is worth is measured in queries the repository never sees, so that is what these
 * assert: a hit means {@code verifyNoInteractions(videoRepository)}, not merely the right answer.
 * The right answer alone would pass with the cache wired up backwards.
 *
 * <p>Plain Mockito rather than a Spring context with a Redis container: the interesting part is
 * which collaborator gets called and which does not, and a real Redis would only make that
 * slower to observe. {@link VideoCacheTest} covers what VideoCache does to Redis itself.
 */
class VideoServiceImplCacheTest {

    private static final String VIDEO_ID = "1234567890";
    private static final Long OWNER = 42L;

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final VideoMapper videoMapper = mock(VideoMapper.class);
    private final VideoCache videoCache = mock(VideoCache.class);

    private final VideoService videoService = new VideoServiceImpl(
            videoRepository,
            videoMapper,
            new SpringDataWebProperties(),
            mock(MinioClient.class),
            new MinioProperties("http://localhost:9000", "key", "secret",
                    "video-media", "us-east-1", Duration.ofMinutes(15)),
            videoCache);

    @Test
    void getById_onCacheHit_neverTouchesMongo() {
        when(videoCache.get(VIDEO_ID)).thenReturn(Optional.of(publicVideo(VIDEO_ID)));

        VideoResponse response = videoService.getById(OWNER, VIDEO_ID);

        assertThat(response.id()).isEqualTo(VIDEO_ID);
        verifyNoInteractions(videoRepository);
    }

    @Test
    void getById_onCacheMiss_readsMongoThenPopulatesTheCache() {
        VideoResponse mapped = publicVideo(VIDEO_ID);
        Video entity = new Video();

        when(videoCache.get(VIDEO_ID)).thenReturn(Optional.empty());
        when(videoRepository.findByIdAndDeletedAtIsNull(VIDEO_ID)).thenReturn(Optional.of(entity));
        when(videoMapper.toResponse(entity)).thenReturn(mapped);

        VideoResponse response = videoService.getById(OWNER, VIDEO_ID);

        assertThat(response).isEqualTo(mapped);
        verify(videoRepository).findByIdAndDeletedAtIsNull(VIDEO_ID);
        verify(videoCache).put(mapped);
    }

    /**
     * An expired entry is indistinguishable from one that was never written — both are an empty
     * Optional — which is exactly why the miss path has to end at Mongo rather than at a 404.
     */
    @Test
    void getById_whenTheEntryHasExpired_goesBackToMongo() {
        Video entity = new Video();
        VideoResponse mapped = publicVideo(VIDEO_ID);

        when(videoCache.get(VIDEO_ID)).thenReturn(Optional.empty());
        when(videoRepository.findByIdAndDeletedAtIsNull(VIDEO_ID)).thenReturn(Optional.of(entity));
        when(videoMapper.toResponse(entity)).thenReturn(mapped);

        assertThat(videoService.getById(OWNER, VIDEO_ID)).isEqualTo(mapped);
    }

    /**
     * The half-warm batch is the one that matters: a ranking is rarely entirely cached or
     * entirely cold, and querying Mongo for ids already in hand is the bug this shape invites.
     */
    @Test
    void getByIds_queriesMongoForTheMissesOnly_andKeepsTheCallersOrder() {
        VideoResponse cached = publicVideo("A");
        VideoResponse fromMongo = publicVideo("B");
        Video entity = new Video();

        when(videoCache.getAll(List.of("A", "B"))).thenReturn(Map.of("A", cached));
        when(videoRepository.findByIdInAndDeletedAtIsNull(List.of("B"))).thenReturn(List.of(entity));
        when(videoMapper.toResponse(entity)).thenReturn(fromMongo);

        List<VideoResponse> response = videoService.getByIds(OWNER, List.of("A", "B"));

        assertThat(response).containsExactly(cached, fromMongo);
        verify(videoRepository).findByIdInAndDeletedAtIsNull(List.of("B"));
        verify(videoCache).putAll(List.of(fromMongo));
    }

    @Test
    void getByIds_onAFullyWarmBatch_neverTouchesMongo() {
        when(videoCache.getAll(List.of("A", "B")))
                .thenReturn(Map.of("A", publicVideo("A"), "B", publicVideo("B")));

        assertThat(videoService.getByIds(OWNER, List.of("A", "B"))).hasSize(2);

        verifyNoInteractions(videoRepository);
    }

    /**
     * The visibility check has to run on the cached value too. One entry serves every viewer —
     * that is what makes the cache worth having — so a private video sitting in it must still be
     * a 404 for everyone but its owner.
     */
    @Test
    void getByIds_filtersAPrivateCachedVideoOutForAStranger() {
        VideoResponse privateVideo = new VideoResponse(
                "A", OWNER, "t", "d", null, null, 10, VideoStatus.PUBLISHED,
                VideoVisibility.PRIVATE, 0, 0, 0, List.of(), Instant.now());

        when(videoCache.getAll(List.of("A"))).thenReturn(Map.of("A", privateVideo));

        assertThat(videoService.getByIds(999L, List.of("A"))).isEmpty();
        assertThat(videoService.getByIds(OWNER, List.of("A"))).containsExactly(privateVideo);
    }

    @Test
    void delete_evictsTheEntryRatherThanLeavingItToExpire() {
        Video entity = Video.builder().id(VIDEO_ID).userId(OWNER).build();
        when(videoRepository.findByIdAndDeletedAtIsNull(VIDEO_ID)).thenReturn(Optional.of(entity));

        videoService.delete(OWNER, VIDEO_ID);

        verify(videoCache).evict(VIDEO_ID);
    }

    /**
     * VideoCache swallows its own Redis failures, so from here a dead Redis looks like a
     * permanent miss. Asserted anyway: the read path must have no branch that treats an empty
     * cache as an answer.
     */
    @Test
    void whenRedisIsDown_readsStillSucceedFromMongo() {
        Video entity = new Video();
        VideoResponse mapped = publicVideo(VIDEO_ID);

        when(videoCache.get(anyString())).thenReturn(Optional.empty());
        when(videoCache.getAll(anyList())).thenReturn(Map.of());
        when(videoRepository.findByIdAndDeletedAtIsNull(VIDEO_ID)).thenReturn(Optional.of(entity));
        when(videoRepository.findByIdInAndDeletedAtIsNull(List.of(VIDEO_ID))).thenReturn(List.of(entity));
        when(videoMapper.toResponse(any(Video.class))).thenReturn(mapped);

        assertThat(videoService.getById(OWNER, VIDEO_ID)).isEqualTo(mapped);
        assertThat(videoService.getByIds(OWNER, List.of(VIDEO_ID))).containsExactly(mapped);
    }

    private static VideoResponse publicVideo(String id) {
        return new VideoResponse(
                id, OWNER, "title", "description", "https://cdn/t.jpg", "https://cdn/v.m3u8",
                10, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 0, 0, 0, List.of(), Instant.now());
    }
}
