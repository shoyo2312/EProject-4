package com.tiktok.storyservice.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.storyservice.dto.request.CreateStoryRequest;
import com.tiktok.storyservice.dto.response.StoryAnnouncementResponse;
import com.tiktok.storyservice.dto.response.StoryCardResponse;
import com.tiktok.storyservice.dto.response.StoryGroupResponse;
import com.tiktok.storyservice.dto.response.StoryResponse;
import com.tiktok.storyservice.dto.response.StoryRingResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryReaction;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryView;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.NotStoryOwnerException;
import com.tiktok.storyservice.exception.OwnStoryReactionException;
import com.tiktok.storyservice.exception.StoryCaptionRejectedException;
import com.tiktok.storyservice.exception.StoryNotFoundException;
import com.tiktok.storyservice.exception.TooManyFollowedUsersException;
import com.tiktok.storyservice.exception.TooManyRingUsersException;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoryServiceImplTest {

    private static final String UPLOAD = "s3://bucket/stories/100/1.jpg";
    private static final String KEY = "stories/100/1.jpg";
    private static final String COPY = "stories/100/55/media.jpg";
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

    private static StoryView view(String storyId, Long viewerId) {
        return StoryView.builder().storyId(storyId).viewerId(viewerId).build();
    }

    // --- create -------------------------------------------------------------------------------

    @Test
    void createSavesPendingWithTextVerdict() {
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(true);
        when(captionModeration.screen("hello")).thenReturn(ModerationVerdict.REVIEW);
        when(storyMedia.copyForStory(eq(KEY), anyString())).thenReturn(COPY);
        when(storyRepository.insert(any(Story.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StoryResponse response = storyService.create(100L, new CreateStoryRequest(UPLOAD, "hello", StoryVisibility.FRIENDS));

        ArgumentCaptor<Story> captor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).insert(captor.capture());
        Story saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(100L);
        assertThat(saved.getMediaKey()).isEqualTo(COPY);
        assertThat(saved.getMediaType()).isEqualTo(StoryMediaType.IMAGE);
        assertThat(saved.getCaption()).isEqualTo("hello");
        assertThat(saved.getStatus()).isEqualTo(StoryStatus.PENDING_MODERATION);
        assertThat(saved.getVisibility()).isEqualTo(StoryVisibility.FRIENDS);
        assertThat(saved.getTextVerdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(saved.getExpiresAt()).isCloseTo(Instant.now().plus(48, ChronoUnit.HOURS), within(2, ChronoUnit.SECONDS));
        assertThat(response.status()).as("the owner's own view").isEqualTo(StoryStatus.PENDING_MODERATION);
    }

    /**
     * The upload form stays good after the story is posted, so whatever sits at the upload key can
     * change after moderation looked at it. The story keeps its own copy, named after its id, and
     * that copy is what media-worker screens (the created event carries the media key) and what
     * viewers are served.
     */
    @Test
    void createServesAndScreensItsOwnCopyOfTheUpload() {
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(true);
        when(storyMedia.copyForStory(eq(KEY), anyString())).thenReturn(COPY);
        when(storyRepository.insert(any(Story.class))).thenAnswer(invocation -> invocation.getArgument(0));

        storyService.create(100L, new CreateStoryRequest(UPLOAD, null, StoryVisibility.PUBLIC));

        ArgumentCaptor<Story> captor = ArgumentCaptor.forClass(Story.class);
        verify(storyRepository).insert(captor.capture());
        Story saved = captor.getValue();
        assertThat(saved.getMediaKey()).as("what is screened and served").isEqualTo(COPY);
        assertThat(saved.getUploadKey()).as("what keeps one upload to one story").isEqualTo(KEY);
        verify(storyMedia).copyForStory(KEY, saved.getId());
        verify(storyMedia).signedGet(COPY);
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
        verify(storyMedia, never()).copyForStory(anyString(), anyString());
    }

    /** The second create copies too, then fails on the unique upload key; its copy is left to the lifecycle rule. */
    @Test
    void secondStoryFromTheSameUploadIsRefused() {
        when(storyMedia.keyOf(100L, UPLOAD)).thenReturn(KEY);
        when(storyMedia.typeOf(KEY)).thenReturn(StoryMediaType.IMAGE);
        when(storyMedia.exists(KEY)).thenReturn(true);
        when(storyMedia.copyForStory(eq(KEY), anyString())).thenReturn(COPY);
        when(storyRepository.insert(any(Story.class))).thenThrow(new DuplicateKeyException("uploadKey"));

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

    // --- rings --------------------------------------------------------------------------------

    @Test
    void ringsMarkOwnRedOnlyWhileAStoryIsActive() {
        Story pending = story("p", 100L, StoryStatus.PENDING_MODERATION, StoryVisibility.PUBLIC, Instant.now());
        Story held = story("h", 100L, StoryStatus.HELD, StoryVisibility.PUBLIC, Instant.now());
        when(storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(eq(100L), any()))
                .thenReturn(List.of(pending))
                .thenReturn(List.of(held, active("a", 100L)))
                .thenReturn(List.of());

        assertThat(storyService.rings(100L, List.of(100L))).containsExactly(new StoryRingResponse(100L, false));
        assertThat(storyService.rings(100L, List.of(100L))).containsExactly(new StoryRingResponse(100L, true));
        assertThat(storyService.rings(100L, List.of(100L))).isEmpty();
        verifyNoInteractions(storyAudience, storyMedia);
    }

    @Test
    void ringsOfOthersFollowWhatTheViewerHasSeen() {
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(7L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(active("s1", 7L), active("s2", 7L)));
        when(storyAudience.resolve(eq(100L), any())).thenReturn(Map.of(7L, PUBLIC_ONLY));
        when(storyViewRepository.findByViewerIdAndStoryIdIn(eq(100L), any()))
                .thenReturn(List.of(view("s1", 100L)))
                .thenReturn(List.of(view("s1", 100L), view("s2", 100L)));

        assertThat(storyService.rings(100L, List.of(7L))).containsExactly(new StoryRingResponse(7L, true));
        assertThat(storyService.rings(100L, List.of(7L))).containsExactly(new StoryRingResponse(7L, false));
    }

    @Test
    void ringsLeaveOutOwnersTheViewerMayNotSee() {
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(7L, 8L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(active("s1", 7L), active("s2", 8L)));
        when(storyAudience.resolve(eq(100L), any()))
                .thenReturn(Map.of(7L, PUBLIC_ONLY, 8L, EnumSet.noneOf(StoryVisibility.class)));

        assertThat(storyService.rings(100L, List.of(7L, 8L))).containsExactly(new StoryRingResponse(7L, true));
        verifyNoInteractions(storyMedia);
    }

    @Test
    void ringsForAGuestAreAllUnseen() {
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(7L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(active("s1", 7L)));
        when(storyAudience.resolve(null, List.of(7L))).thenReturn(Map.of(7L, PUBLIC_ONLY));

        assertThat(storyService.rings(null, List.of(7L))).containsExactly(new StoryRingResponse(7L, true));
        verifyNoInteractions(storyViewRepository);
    }

    @Test
    void ringsRejectMoreThan100Ids() {
        List<Long> ids = LongStream.rangeClosed(1, 101).boxed().toList();

        assertThatThrownBy(() -> storyService.rings(100L, ids)).isInstanceOf(TooManyRingUsersException.class);
        verifyNoInteractions(storyRepository);
    }

    /** The web asks in chunks of exactly 100: the cap refuses the 101st id, not the 100th. */
    @Test
    void ringsAcceptExactly100Ids() {
        List<Long> ids = LongStream.rangeClosed(1, 100).boxed().toList();
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(ids), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(active("s1", 7L)));
        when(storyAudience.resolve(null, List.of(7L))).thenReturn(Map.of(7L, PUBLIC_ONLY));

        assertThat(storyService.rings(null, ids)).containsExactly(new StoryRingResponse(7L, true));
    }

    @Test
    void ringsOfNobodyAskNothing() {
        assertThat(storyService.rings(100L, null)).isEmpty();
        assertThat(storyService.rings(100L, List.of())).isEmpty();
        verifyNoInteractions(storyRepository);
    }

    /** What the viewer may not see cannot keep a ring red: it would promise a story that is not there. */
    @Test
    void ringsCountOnlyTheStoriesTheViewerMaySee() {
        Story friendsOnly = story("s2", 7L, StoryStatus.ACTIVE, StoryVisibility.FRIENDS, Instant.now());
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(7L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(active("s1", 7L), friendsOnly));
        when(storyAudience.resolve(eq(100L), any())).thenReturn(Map.of(7L, PUBLIC_ONLY));
        when(storyViewRepository.findByViewerIdAndStoryIdIn(100L, List.of("s1"))).thenReturn(List.of(view("s1", 100L)));

        assertThat(storyService.rings(100L, List.of(7L))).containsExactly(new StoryRingResponse(7L, false));
    }

    /**
     * The own ring comes from its own query, so it must be slotted back where the caller put it; a
     * repeated id and one with no story drop out.
     */
    @Test
    void ringsComeBackInTheOrderAsked() {
        when(storyRepository.findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(eq(100L), any()))
                .thenReturn(List.of(active("o", 100L)));
        when(storyRepository.findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                eq(List.of(8L, 7L, 9L)), eq(StoryStatus.ACTIVE), any())).thenReturn(List.of(active("s1", 7L), active("s2", 8L)));
        when(storyAudience.resolve(eq(100L), any())).thenReturn(Map.of(7L, PUBLIC_ONLY, 8L, PUBLIC_ONLY));

        assertThat(storyService.rings(100L, List.of(8L, 100L, 7L, 8L, 9L)))
                .extracting(StoryRingResponse::userId).containsExactly(8L, 100L, 7L);
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

    // --- reactions ----------------------------------------------------------------------------

    @Test
    void react_ownerReactingToOwnStory_isBadRequest() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));

        assertThatThrownBy(() -> storyService.react(1L, "s1", StoryReaction.LOVE))
                .isInstanceOf(OwnStoryReactionException.class);
        verifyNoInteractions(storyViewRepository);
    }

    @Test
    void react_firstTouchInsertsTheViewAndCountsIt() {
        Story story = active("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));
        when(storyViewRepository.upsertReaction(eq("s1"), eq(2L), eq(StoryReaction.LOVE), eq(story.getExpiresAt()), any()))
                .thenReturn(true);

        storyService.react(2L, "s1", StoryReaction.LOVE);

        verify(storyRepository).incrementViewCount("s1");
    }

    @Test
    void react_afterAViewDoesNotCountAgain() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));
        when(storyViewRepository.upsertReaction(any(), any(), any(), any(), any())).thenReturn(false);

        storyService.react(2L, "s1", StoryReaction.WOW);

        verify(storyRepository, never()).incrementViewCount(any());
    }

    @Test
    void react_onAStoryTheViewerMayNotSee_isNotFound() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1"))
                .thenReturn(Optional.of(story("s1", 1L, StoryStatus.ACTIVE, StoryVisibility.PRIVATE, Instant.now())));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));

        assertThatThrownBy(() -> storyService.react(2L, "s1", StoryReaction.SAD)).isInstanceOf(StoryNotFoundException.class);
        verifyNoInteractions(storyViewRepository);
    }

    @Test
    void unreact_isIdempotent() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));

        storyService.unreact(2L, "s1");
        storyService.unreact(2L, "s1");

        verify(storyViewRepository, times(2)).clearReaction("s1", 2L);
        verify(storyRepository, never()).incrementViewCount(any());
    }

    @Test
    void getById_asViewer_carriesMyReactionAndNoCounts() {
        Story story = active("s1", 1L);
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(story));
        when(storyAudience.resolve(2L, List.of(1L))).thenReturn(Map.of(1L, PUBLIC_ONLY));
        when(storyViewRepository.findByViewerIdAndStoryIdIn(2L, List.of("s1")))
                .thenReturn(List.of(StoryView.builder().storyId("s1").viewerId(2L).reaction(StoryReaction.LOVE).build()));

        StoryResponse response = storyService.getById(2L, "s1");

        assertThat(response.myReaction()).isEqualTo(StoryReaction.LOVE);
        assertThat(response.viewedByMe()).isTrue();
        assertThat(response.reactionCounts()).isNull();
        verify(storyViewRepository, never()).countReactions(any());
    }

    @Test
    void getById_asOwner_carriesCountsAndNoMyReaction() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));
        when(storyViewRepository.countReactions("s1")).thenReturn(Map.of(StoryReaction.LOVE, 2L));

        StoryResponse response = storyService.getById(1L, "s1");

        assertThat(response.reactionCounts()).containsExactly(Map.entry(StoryReaction.LOVE, 2L));
        assertThat(response.myReaction()).isNull();
    }

    @Test
    void listViewers_carriesEachViewersReaction() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1")).thenReturn(Optional.of(active("s1", 1L)));
        StoryView view = StoryView.builder().storyId("s1").viewerId(2L).viewedAt(Instant.now())
                .reaction(StoryReaction.ANGRY).build();
        when(storyViewRepository.findByStoryIdAndViewedAtBeforeOrderByViewedAtDesc(eq("s1"), any(), any()))
                .thenReturn(List.of(view));

        assertThat(storyService.listViewers(1L, "s1", null, null)).singleElement()
                .satisfies(viewer -> assertThat(viewer.reaction()).isEqualTo(StoryReaction.ANGRY));
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

    // --- announcement -------------------------------------------------------------------------

    @Test
    void announcementOfALiveStoryNamesOwnerAndVisibility() {
        when(storyRepository.findByIdAndDeletedAtIsNull("s1"))
                .thenReturn(Optional.of(story("s1", 7L, StoryStatus.ACTIVE, StoryVisibility.FRIENDS, Instant.now())));
        when(storyAudience.isHidden(7L)).thenReturn(false);

        assertThat(storyService.announcement("s1")).isEqualTo(new StoryAnnouncementResponse(7L, StoryVisibility.FRIENDS));
    }

    /**
     * Owner 7 is the hidden one and their story is otherwise live; the other stories are owner 8's
     * and each is wrong in one way, so no missing check can hide behind the ban.
     */
    @Test
    void announcementOfAStoryThatCannotBeAnnouncedIsNotFound() {
        Story expired = Story.builder().id("expired").userId(8L).status(StoryStatus.ACTIVE)
                .visibility(StoryVisibility.PUBLIC).expiresAt(Instant.now().minusSeconds(1)).build();
        Story held = story("held", 8L, StoryStatus.HELD, StoryVisibility.PUBLIC, Instant.now());
        when(storyRepository.findByIdAndDeletedAtIsNull("gone")).thenReturn(Optional.empty());
        when(storyRepository.findByIdAndDeletedAtIsNull("expired")).thenReturn(Optional.of(expired));
        when(storyRepository.findByIdAndDeletedAtIsNull("held")).thenReturn(Optional.of(held));
        when(storyRepository.findByIdAndDeletedAtIsNull("banned")).thenReturn(Optional.of(active("banned", 7L)));
        when(storyAudience.isHidden(anyLong())).thenAnswer(call -> call.<Long>getArgument(0) == 7L);

        for (String storyId : List.of("gone", "expired", "held", "banned")) {
            assertThatThrownBy(() -> storyService.announcement(storyId))
                    .as(storyId).isInstanceOf(StoryNotFoundException.class);
        }
    }

    // --- viewable (internal, for chat) --------------------------------------------------------

    @Test
    void viewableReturnsOnlyStoriesInTheViewersAudienceAndSignsEachForThem() {
        Story visible = active("visible", 7L);
        Story outside = active("outside", 8L);
        Story held = story("held", 7L, StoryStatus.HELD, StoryVisibility.PUBLIC, Instant.now());
        Story expired = Story.builder().id("expired").userId(7L).status(StoryStatus.ACTIVE)
                .visibility(StoryVisibility.PUBLIC).expiresAt(Instant.now().minusSeconds(1)).build();
        when(storyRepository.findAllById(any())).thenReturn(List.of(visible, outside, held, expired));
        when(storyAudience.resolve(eq(1L), any())).thenReturn(Map.of(7L, Set.of(StoryVisibility.PUBLIC), 8L, Set.of()));
        when(storyMedia.signedGet(any())).thenReturn("https://signed");

        List<StoryCardResponse> cards = storyService.viewable(1L, List.of("visible", "outside", "held", "expired", "gone"));

        assertThat(cards).extracting(StoryCardResponse::id).containsExactly("visible");
        assertThat(cards.get(0).userId()).isEqualTo(7L);
        assertThat(cards.get(0).mediaUrl()).isEqualTo("https://signed");
    }

    @Test
    void viewableLetsTheOwnerSeeTheirOwnActiveStoryButNotAHeldOne() {
        Story own = active("own", 1L);
        Story heldOwn = story("heldOwn", 1L, StoryStatus.HELD, StoryVisibility.PUBLIC, Instant.now());
        when(storyRepository.findAllById(any())).thenReturn(List.of(own, heldOwn));
        when(storyMedia.signedGet(any())).thenReturn("https://signed");

        assertThat(storyService.viewable(1L, List.of("own", "heldOwn")))
                .extracting(StoryCardResponse::id).containsExactly("own");
        verifyNoInteractions(storyAudience);
    }
}
