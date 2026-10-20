package com.tiktok.storyservice.service;

import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.StoryCaptionRejectedException;
import com.tiktok.storyservice.exception.UploadNotFoundException;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.storyservice.entity.StoryView;
import com.tiktok.storyservice.exception.NotStoryOwnerException;
import com.tiktok.storyservice.exception.StoryNotFoundException;
import com.tiktok.storyservice.mapper.StoryMapper;
import com.tiktok.storyservice.repository.StoryRepository;
import com.tiktok.storyservice.repository.StoryViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoryServiceImplTest {

    private static final long TTL_HOURS = 24L;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private StoryViewRepository storyViewRepository;

    @Mock
    private StoryMapper storyMapper;

    @Mock
    private StoryMedia storyMedia;

    @Mock
    private StoryCaptionModeration captionModeration;

    private StoryServiceImpl storyService;

    @BeforeEach
    void setUp() {
        storyService = new StoryServiceImpl(storyRepository, storyViewRepository, storyMapper, storyMedia, captionModeration);
        ReflectionTestUtils.setField(storyService, "ttlHours", TTL_HOURS);
    }

    private Story visibleStory(String id, Long userId) {
        return Story.builder()
                .id(id)
                .userId(userId)
                .mediaKey("stories/1/1.jpg")
                .mediaType(StoryMediaType.IMAGE)
                .viewCount(0)
                .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
    }

    private Story expiredStory(String id, Long userId) {
        return Story.builder()
                .id(id)
                .userId(userId)
                .mediaKey("stories/1/1.jpg")
                .mediaType(StoryMediaType.IMAGE)
                .viewCount(0)
                .createdAt(Instant.now().minus(30, ChronoUnit.HOURS))
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
    }

    private static final String UPLOAD = "s3://bucket/stories/100/1.jpg";
    private static final String KEY = "stories/100/1.jpg";

    @Test
    void createSavesPendingWithTextVerdict() {
        CreateStoryRequest request = new CreateStoryRequest(UPLOAD, "hello", StoryVisibility.FRIENDS);
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(true);
        when(captionModeration.screen("hello")).thenReturn(ModerationVerdict.REVIEW);
        when(storyRepository.insert(any(Story.class))).thenAnswer(invocation -> invocation.getArgument(0));

        storyService.create(100L, request);

        ArgumentCaptor<Story> captor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).insert(captor.capture());
        Story saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getMediaKey()).isEqualTo(KEY);
        assertThat(saved.getMediaType()).isEqualTo(StoryMediaType.IMAGE);
        assertThat(saved.getCaption()).isEqualTo("hello");
        assertThat(saved.getStatus()).isEqualTo(StoryStatus.PENDING_MODERATION);
        assertThat(saved.getVisibility()).isEqualTo(StoryVisibility.FRIENDS);
        assertThat(saved.getTextVerdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(saved.getExpiresAt()).isCloseTo(Instant.now().plus(48, ChronoUnit.HOURS), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void missingObjectIsRejected() {
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(false);

        assertThatThrownBy(() -> storyService.create(100L, new CreateStoryRequest(UPLOAD, null, StoryVisibility.PUBLIC)))
                .isInstanceOf(UploadNotFoundException.class);
        verifyNoInteractions(storyRepository);
    }

    @Test
    void rejectedCaptionSavesNothing() {
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(true);
        when(captionModeration.screen("bad")).thenThrow(new StoryCaptionRejectedException());

        assertThatThrownBy(() -> storyService.create(100L, new CreateStoryRequest(UPLOAD, "bad", StoryVisibility.PUBLIC)))
                .isInstanceOf(StoryCaptionRejectedException.class);
        verifyNoInteractions(storyRepository);
    }

    @Test
    void secondStoryFromTheSameUploadIsRefused() {
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(true);
        when(storyRepository.insert(any(Story.class))).thenThrow(new DuplicateKeyException("mediaKey"));

        assertThatThrownBy(() -> storyService.create(100L, new CreateStoryRequest(UPLOAD, null, StoryVisibility.PUBLIC)))
                .isInstanceOf(ForeignUploadException.class);
    }

    @Test
    void getById_visibleStory_returnsMappedResponse() {
        Story story = visibleStory("s1", 1L);
        StoryResponse expected = new StoryResponse("s1", 1L, story.getMediaKey(), story.getMediaType(), null, 0, story.getCreatedAt(), story.getExpiresAt());
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyMapper.toResponse(story)).thenReturn(expected);

        StoryResponse response = storyService.getById("s1");

        assertThat(response).isEqualTo(expected);
    }

    @Test
    void getById_missingStory_throwsStoryNotFound() {
        when(storyRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.getById("missing"))
                .isInstanceOf(StoryNotFoundException.class);
    }

    @Test
    void getById_expiredStory_throwsStoryNotFound() {
        Story story = expiredStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        assertThatThrownBy(() -> storyService.getById("s1"))
                .isInstanceOf(StoryNotFoundException.class);
    }

    @Test
    void listByUser_mapsAllVisibleStories() {
        Story story1 = visibleStory("s1", 1L);
        Story story2 = visibleStory("s2", 1L);
        StoryResponse response1 = new StoryResponse("s1", 1L, story1.getMediaKey(), story1.getMediaType(), null, 0, story1.getCreatedAt(), story1.getExpiresAt());
        StoryResponse response2 = new StoryResponse("s2", 1L, story2.getMediaKey(), story2.getMediaType(), null, 0, story2.getCreatedAt(), story2.getExpiresAt());
        when(storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(1L), any(Instant.class)))
                .thenReturn(List.of(story1, story2));
        when(storyMapper.toResponse(story1)).thenReturn(response1);
        when(storyMapper.toResponse(story2)).thenReturn(response2);

        List<StoryResponse> responses = storyService.listByUser(1L);

        assertThat(responses).containsExactly(response1, response2);
    }

    @Test
    void listFeed_emptyFollowedUserIds_returnsEmptyListWithoutQuerying() {
        List<StoryResponse> responses = storyService.listFeed(List.of());

        assertThat(responses).isEmpty();
        verifyNoInteractions(storyRepository);
    }

    @Test
    void listFeed_nullFollowedUserIds_returnsEmptyListWithoutQuerying() {
        List<StoryResponse> responses = storyService.listFeed(null);

        assertThat(responses).isEmpty();
        verifyNoInteractions(storyRepository);
    }

    @Test
    void listFeed_withFollowedUsers_mapsVisibleStories() {
        Story story = visibleStory("s1", 2L);
        List<Long> followedIds = List.of(2L, 3L);
        StoryResponse response = new StoryResponse("s1", 2L, story.getMediaKey(), story.getMediaType(), null, 0, story.getCreatedAt(), story.getExpiresAt());
        when(storyRepository.findByUserIdInAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(followedIds), any(Instant.class)))
                .thenReturn(List.of(story));
        when(storyMapper.toResponse(story)).thenReturn(response);

        List<StoryResponse> responses = storyService.listFeed(followedIds);

        assertThat(responses).containsExactly(response);
    }

    @Test
    void recordView_ownerViewingOwnStory_isNoOp() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        storyService.recordView(1L, "s1");

        verifyNoInteractions(storyViewRepository);
        verify(storyRepository, never()).save(any());
    }

    @Test
    void duplicateViewIsNoOp() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyViewRepository.insert(any(StoryView.class))).thenThrow(new DuplicateKeyException("dup"));

        storyService.recordView(2L, "s1");

        verify(storyRepository, never()).incrementViewCount(any());
        verify(storyRepository, never()).save(any());
    }

    @Test
    void firstViewIncrementsAtomically() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        storyService.recordView(2L, "s1");

        ArgumentCaptor<StoryView> viewCaptor = ArgumentCaptor.forClass(StoryView.class);
        verify(storyViewRepository).insert(viewCaptor.capture());
        assertThat(viewCaptor.getValue().getStoryId()).isEqualTo("s1");
        assertThat(viewCaptor.getValue().getViewerId()).isEqualTo(2L);
        assertThat(viewCaptor.getValue().getExpiresAt()).isEqualTo(story.getExpiresAt());
        verify(storyRepository).incrementViewCount("s1");
        verify(storyRepository, never()).save(any());
    }

    @Test
    void recordView_missingStory_throwsStoryNotFound() {
        when(storyRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.recordView(2L, "missing"))
                .isInstanceOf(StoryNotFoundException.class);
    }

    @Test
    void listViewers_calledByOwner_returnsMappedViewers() {
        Story story = visibleStory("s1", 1L);
        StoryView view = StoryView.builder().storyId("s1").viewerId(2L).build();
        StoryViewerResponse expected = new StoryViewerResponse(2L, Instant.now());
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyViewRepository.findByStoryIdAndViewedAtBeforeOrderByViewedAtDesc(eq("s1"), any(Instant.class), any())).thenReturn(List.of(view));
        when(storyMapper.toViewerResponse(view)).thenReturn(expected);

        List<StoryViewerResponse> viewers = storyService.listViewers(1L, "s1");

        assertThat(viewers).containsExactly(expected);
    }

    @Test
    void listViewers_calledByNonOwner_throwsNotStoryOwner() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        assertThatThrownBy(() -> storyService.listViewers(2L, "s1"))
                .isInstanceOf(NotStoryOwnerException.class);
        verifyNoInteractions(storyViewRepository);
    }

    @Test
    void listViewers_missingStory_throwsStoryNotFound() {
        when(storyRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.listViewers(1L, "missing"))
                .isInstanceOf(StoryNotFoundException.class);
    }

    @Test
    void delete_calledByOwner_marksStoryDeleted() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        storyService.delete(1L, "s1");

        ArgumentCaptor<Story> captor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).save(captor.capture());
        assertThat(captor.getValue().isDeleted()).isTrue();
    }

    @Test
    void delete_calledByNonOwner_throwsNotStoryOwnerAndDoesNotSave() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        assertThatThrownBy(() -> storyService.delete(2L, "s1"))
                .isInstanceOf(NotStoryOwnerException.class);
        verify(storyRepository, never()).save(any());
    }

    @Test
    void delete_missingStory_throwsStoryNotFound() {
        when(storyRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.delete(1L, "missing"))
                .isInstanceOf(StoryNotFoundException.class);
    }
}
