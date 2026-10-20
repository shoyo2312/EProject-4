package com.tiktok.storyservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.response.StoryGroupResponse;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryView;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.NotStoryOwnerException;
import com.tiktok.storyservice.exception.StoryCaptionRejectedException;
import com.tiktok.storyservice.exception.StoryNotFoundException;
import com.tiktok.storyservice.exception.TooManyFollowedUsersException;
import com.tiktok.storyservice.exception.UploadNotFoundException;
import com.tiktok.storyservice.mapper.StoryMapper;
import com.tiktok.storyservice.repository.StoryRepository;
import com.tiktok.storyservice.repository.StoryViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoryServiceImplTest {

    private static final String UPLOAD = "s3://bucket/stories/100/1.jpg";
    private static final String KEY = "stories/100/1.jpg";
    private static final Set<StoryVisibility> PUBLIC_ONLY = EnumSet.of(StoryVisibility.PUBLIC);

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private StoryViewRepository storyViewRepository;

    @Mock
    private StoryMedia storyMedia;

    @Mock
    private StoryCaptionModeration captionModeration;

    @Mock
    private StoryAudience storyAudience;

    private final StoryMapper storyMapper = Mappers.getMapper(StoryMapper.class);

    private StoryServiceImpl storyService;

    @BeforeEach
    void setUp() {
        storyService = new StoryServiceImpl(storyRepository, storyViewRepository, storyMapper, storyMedia,
                captionModeration, storyAudience);
    }

    private static Story story(String id, Long userId, StoryStatus status, StoryVisibility visibility, Instant createdAt) {
        return Story.builder()
                .id(id)
                .userId(userId)
                .mediaKey("stories/" + userId + "/" + id + ".jpg")
                .mediaType(StoryMediaType.IMAGE)
                .status(status)
                .visibility(visibility)
                .viewCount(3)
                .createdAt(createdAt)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
    }

    private static Story active(String id, Long userId) {
        return story(id, userId, StoryStatus.ACTIVE, StoryVisibility.PUBLIC, Instant.now().minus(1, ChronoUnit.HOURS));
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    void createSavesPendingWithTextVerdict() {
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(true);
        when(captionModeration.screen("hello")).thenReturn(ModerationVerdict.REVIEW);
        when(storyRepository.insert(any(Story.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StoryResponse response = storyService.create(100L, new CreateStoryRequest(UPLOAD, "hello", StoryVisibility.FRIENDS));

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
        assertThat(response.status()).as("the owner's own view").isEqualTo(StoryStatus.PENDING_MODERATION);
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

    // --- single reads -------------------------------------------------------------------------

    @Test
    void getByIdHidesFriendsStoryFromStranger() {
        Story story = story("s1", 1L, StoryStatus.ACTIVE, StoryVisibility.FRIENDS, Instant.now());
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));

        assertThatThrownBy(() -> storyService.getById(2L, "s1")).isInstanceOf(StoryNotFoundException.class);
        verify(storyMedia, never()).signedGet(anyString());
    }

    @Test
    void getByIdShowsAPublicStoryWithoutTheOwnersFields() {
        Story story = active("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyAudience.resolve(null, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));
        when(storyMedia.signedGet(story.getMediaKey())).thenReturn("https://media/signed");

        StoryResponse response = storyService.getById(null, "s1");

        assertThat(response.mediaUrl()).isEqualTo("https://media/signed");
        assertThat(response.status()).isNull();
        assertThat(response.viewCount()).isNull();
    }

    @Test
    void getByIdHidesAHeldStoryFromEveryoneButItsOwner() {
        Story story = story("s1", 1L, StoryStatus.HELD, StoryVisibility.PUBLIC, Instant.now());
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        assertThatThrownBy(() -> storyService.getById(2L, "s1")).isInstanceOf(StoryNotFoundException.class);
        assertThat(storyService.getById(1L, "s1").status()).isEqualTo(StoryStatus.HELD);
    }

    @Test
    void getByIdOfAnExpiredStoryIsNotFound() {
        Story story = Story.builder().id("s1").userId(1L).status(StoryStatus.ACTIVE).visibility(StoryVisibility.PUBLIC)
                .expiresAt(Instant.now().minusSeconds(1)).build();
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));

        assertThatThrownBy(() -> storyService.getById(1L, "s1")).isInstanceOf(StoryNotFoundException.class);
    }

    @Test
    void ownerSeesPendingWithStatus() {
        Story pending = story("p", 1L, StoryStatus.PENDING_MODERATION, StoryVisibility.PUBLIC, Instant.now());
        when(storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(eq(1L), any()))
                .thenReturn(List.of(pending));

        List<StoryResponse> own = storyService.listByUser(1L, 1L);

        assertThat(own).extracting(StoryResponse::status).containsExactly(StoryStatus.PENDING_MODERATION);
        assertThat(own.getFirst().viewCount()).isEqualTo(3L);
        verifyNoInteractions(storyAudience);
    }

    @Test
    void strangerSeesOnlyTheActiveStoriesTheirAudienceAllows() {
        Story publicStory = active("a", 1L);
        Story friendsStory = story("b", 1L, StoryStatus.ACTIVE, StoryVisibility.FRIENDS, Instant.now());
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(1L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(publicStory, friendsStory));
        when(storyAudience.resolve(eq(2L), any())).thenReturn(Map.of(1L, PUBLIC_ONLY));

        assertThat(storyService.listByUser(2L, 1L)).extracting(StoryResponse::id).containsExactly("a");
    }

    // --- feed ---------------------------------------------------------------------------------

    @Test
    void feedPutsOwnGroupFirstThenUnseen() {
        Instant now = Instant.now();
        Story own = story("o", 9L, StoryStatus.PENDING_MODERATION, StoryVisibility.PUBLIC, now);
        Story seenByViewer = story("a1", 1L, StoryStatus.ACTIVE, StoryVisibility.PUBLIC, now.minusSeconds(10));
        Story unseen = story("b1", 2L, StoryStatus.ACTIVE, StoryVisibility.PUBLIC, now.minusSeconds(600));
        when(storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(eq(9L), any()))
                .thenReturn(List.of(own));
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(1L, 2L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(seenByViewer, unseen));
        when(storyAudience.resolve(eq(9L), any())).thenReturn(Map.of(1L, PUBLIC_ONLY, 2L, PUBLIC_ONLY));
        when(storyViewRepository.findByViewerIdAndStoryIdIn(eq(9L), any()))
                .thenReturn(List.of(StoryView.builder().storyId("a1").viewerId(9L).build()));

        List<StoryGroupResponse> feed = storyService.feed(9L, List.of(1L, 2L, 9L));

        assertThat(feed).extracting(StoryGroupResponse::userId).containsExactly(9L, 2L, 1L);
        assertThat(feed.get(0).hasUnseen()).isFalse();
        assertThat(feed.get(1).hasUnseen()).isTrue();
        assertThat(feed.get(2).hasUnseen()).isFalse();
        assertThat(feed.get(2).stories().getFirst().viewedByMe()).isTrue();
    }

    @Test
    void feedDropsOwnersTheViewerMayNotSee() {
        when(storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(eq(9L), any()))
                .thenReturn(List.of());
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(1L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(active("a", 1L)));
        when(storyAudience.resolve(eq(9L), any())).thenReturn(Map.of(1L, EnumSet.noneOf(StoryVisibility.class)));

        assertThat(storyService.feed(9L, List.of(1L))).isEmpty();
    }

    @Test
    void feedRejectsMoreThan500Ids() {
        List<Long> ids = LongStream.rangeClosed(1, 501).boxed().toList();

        assertThatThrownBy(() -> storyService.feed(9L, ids)).isInstanceOf(TooManyFollowedUsersException.class);
        verifyNoInteractions(storyRepository);
    }

    // --- views --------------------------------------------------------------------------------

    @Test
    void recordView_ownerViewingOwnStory_isNoOp() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));

        storyService.recordView(1L, "s1");

        verifyNoInteractions(storyViewRepository);
    }

    @Test
    void recordViewOfAStoryTheViewerMayNotSeeIsNotFound() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1"))
                .thenReturn(Optional.of(story("s1", 1L, StoryStatus.ACTIVE, StoryVisibility.PRIVATE, Instant.now())));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));

        assertThatThrownBy(() -> storyService.recordView(2L, "s1")).isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(storyViewRepository);
    }

    @Test
    void duplicateViewIsNoOp() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));
        when(storyViewRepository.insert(any(StoryView.class))).thenThrow(new DuplicateKeyException("dup"));

        storyService.recordView(2L, "s1");

        verify(storyRepository, never()).incrementViewCount(any());
    }

    @Test
    void firstViewIncrementsAtomically() {
        Story story = active("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));

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
    void listViewersPagesBeforeACursorAndClampsTheSize() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));
        Instant before = Instant.parse("2026-10-20T10:00:00Z");
        StoryView view = StoryView.builder().storyId("s1").viewerId(2L).viewedAt(before.minusSeconds(5)).build();
        when(storyViewRepository.findByStoryIdAndViewedAtBeforeOrderByViewedAtDesc(eq("s1"), eq(before), any()))
                .thenReturn(List.of(view));

        assertThat(storyService.listViewers(1L, "s1", before, 500)).singleElement()
                .satisfies(viewer -> assertThat(viewer.viewerId()).isEqualTo(2L));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(storyViewRepository).findByStoryIdAndViewedAtBeforeOrderByViewedAtDesc(eq("s1"), eq(before), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    void listViewers_calledByNonOwner_throwsNotStoryOwner() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));

        assertThatThrownBy(() -> storyService.listViewers(2L, "s1", null, null))
                .isInstanceOf(NotStoryOwnerException.class);
        verifyNoInteractions(storyViewRepository);
    }

    // --- owner changes ------------------------------------------------------------------------

    @Test
    void ownerChangesVisibility() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));

        StoryResponse response = storyService.updateVisibility(1L, "s1", StoryVisibility.PRIVATE);

        verify(storyRepository).updateVisibility("s1", StoryVisibility.PRIVATE);
        assertThat(response.visibility()).isEqualTo(StoryVisibility.PRIVATE);
    }

    @Test
    void strangerCannotChangeVisibility() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));

        assertThatThrownBy(() -> storyService.updateVisibility(2L, "s1", StoryVisibility.PUBLIC))
                .isInstanceOf(NotStoryOwnerException.class);
        verify(storyRepository, never()).updateVisibility(anyString(), any());
    }

    @Test
    void delete_calledByOwner_marksStoryDeleted() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));

        storyService.delete(1L, "s1");

        verify(storyRepository).markDeleted(eq("s1"), any(Instant.class));
    }

    @Test
    void delete_calledByNonOwner_throwsNotStoryOwnerAndDoesNotSave() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));

        assertThatThrownBy(() -> storyService.delete(2L, "s1")).isInstanceOf(NotStoryOwnerException.class);
        verify(storyRepository, never()).markDeleted(anyString(), any());
    }

    @Test
    void delete_missingStory_throwsStoryNotFound() {
        when(storyRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> storyService.delete(1L, "missing")).isInstanceOf(StoryNotFoundException.class);
    }
}
