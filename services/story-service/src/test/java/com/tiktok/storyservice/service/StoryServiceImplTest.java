package com.tiktok.storyservice.service;

import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryViewerResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
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

    private StoryServiceImpl storyService;

    @BeforeEach
    void setUp() {
        storyService = new StoryServiceImpl(storyRepository, storyViewRepository, storyMapper);
        ReflectionTestUtils.setField(storyService, "ttlHours", TTL_HOURS);
    }

    private Story visibleStory(String id, Long userId) {
        return Story.builder()
                .id(id)
                .userId(userId)
                .mediaUrl("http://media/1.jpg")
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
                .mediaUrl("http://media/1.jpg")
                .mediaType(StoryMediaType.IMAGE)
                .viewCount(0)
                .createdAt(Instant.now().minus(30, ChronoUnit.HOURS))
                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
    }

    @Test
    void create_buildsStoryWithTtlExpiryAndReturnsMappedResponse() {
        CreateStoryRequest request = new CreateStoryRequest("http://media/1.jpg", StoryMediaType.IMAGE, "hello");
        when(storyRepository.save(any(Story.class))).thenAnswer(invocation -> invocation.getArgument(0));
        StoryResponse expected = new StoryResponse("1", 100L, "http://media/1.jpg", StoryMediaType.IMAGE, "hello", 0, Instant.now(), Instant.now());
        when(storyMapper.toResponse(any(Story.class))).thenReturn(expected);

        StoryResponse response = storyService.create(100L, request);

        ArgumentCaptor<Story> captor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).save(captor.capture());
        Story saved = captor.getValue();

        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getMediaUrl()).isEqualTo("http://media/1.jpg");
        assertThat(saved.getMediaType()).isEqualTo(StoryMediaType.IMAGE);
        assertThat(saved.getCaption()).isEqualTo("hello");
        assertThat(saved.getViewCount()).isZero();
        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getExpiresAt()).isCloseTo(Instant.now().plus(TTL_HOURS, ChronoUnit.HOURS), within(2, ChronoUnit.SECONDS));
        assertThat(response).isEqualTo(expected);
    }

    @Test
    void getById_visibleStory_returnsMappedResponse() {
        Story story = visibleStory("s1", 1L);
        StoryResponse expected = new StoryResponse("s1", 1L, story.getMediaUrl(), story.getMediaType(), null, 0, story.getCreatedAt(), story.getExpiresAt());
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
        StoryResponse response1 = new StoryResponse("s1", 1L, story1.getMediaUrl(), story1.getMediaType(), null, 0, story1.getCreatedAt(), story1.getExpiresAt());
        StoryResponse response2 = new StoryResponse("s2", 1L, story2.getMediaUrl(), story2.getMediaType(), null, 0, story2.getCreatedAt(), story2.getExpiresAt());
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
        StoryResponse response = new StoryResponse("s1", 2L, story.getMediaUrl(), story.getMediaType(), null, 0, story.getCreatedAt(), story.getExpiresAt());
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
    void recordView_alreadyViewedByViewer_isNoOpAndDoesNotDuplicateOrIncrement() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyViewRepository.existsByStoryIdAndViewerId("s1", 2L)).thenReturn(true);

        storyService.recordView(2L, "s1");

        verify(storyViewRepository, never()).save(any());
        verify(storyRepository, never()).save(any());
    }

    @Test
    void recordView_newViewer_savesViewAndIncrementsStoryViewCount() {
        Story story = visibleStory("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyViewRepository.existsByStoryIdAndViewerId("s1", 2L)).thenReturn(false);

        storyService.recordView(2L, "s1");

        ArgumentCaptor<StoryView> viewCaptor = ArgumentCaptor.forClass(StoryView.class);
        verify(storyViewRepository).save(viewCaptor.capture());
        assertThat(viewCaptor.getValue().getStoryId()).isEqualTo("s1");
        assertThat(viewCaptor.getValue().getViewerId()).isEqualTo(2L);
        assertThat(viewCaptor.getValue().getExpiresAt()).isEqualTo(story.getExpiresAt());

        ArgumentCaptor<Story> storyCaptor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).save(storyCaptor.capture());
        assertThat(storyCaptor.getValue().getViewCount()).isEqualTo(1L);
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
        when(storyViewRepository.findByStoryIdOrderByViewedAtDesc("s1")).thenReturn(List.of(view));
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
