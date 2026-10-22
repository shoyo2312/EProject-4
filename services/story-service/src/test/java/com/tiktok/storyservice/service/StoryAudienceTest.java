package com.tiktok.storyservice.service;

import com.tiktok.storyservice.client.FriendshipClient;
import com.tiktok.storyservice.client.FriendshipClient.Relationship;
import com.tiktok.storyservice.entity.OwnerBan;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.repository.OwnerBanRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static com.tiktok.storyservice.entity.StoryVisibility.FRIENDS;
import static com.tiktok.storyservice.entity.StoryVisibility.PRIVATE;
import static com.tiktok.storyservice.entity.StoryVisibility.PUBLIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StoryAudienceTest {

    @Test
    void ownerSeesEverything() {
        assertThat(StoryAudience.visibilitiesFor(true, true, false, true, false, false))
                .containsExactlyInAnyOrder(PUBLIC, FRIENDS, PRIVATE);
    }

    @Test
    void hiddenOwnerShowsNothing() {
        assertThat(StoryAudience.visibilitiesFor(false, true, false, false, true, true)).isEmpty();
    }

    @Test
    void blockEitherWayShowsNothing() {
        assertThat(StoryAudience.visibilitiesFor(false, false, true, false, true, true)).isEmpty();
    }

    @Test
    void friendSeesPublicAndFriends() {
        assertThat(StoryAudience.visibilitiesFor(false, false, false, true, true, true))
                .containsExactlyInAnyOrder(PUBLIC, FRIENDS);
    }

    @Test
    void followerOfAPrivateOwnerSeesPublic() {
        assertThat(StoryAudience.visibilitiesFor(false, false, false, true, true, false)).containsExactly(PUBLIC);
    }

    @Test
    void strangerToAPrivateOwnerSeesNothing() {
        assertThat(StoryAudience.visibilitiesFor(false, false, false, true, false, false)).isEmpty();
    }

    @Test
    void strangerToAPublicOwnerSeesPublic() {
        assertThat(StoryAudience.visibilitiesFor(false, false, false, false, false, false)).containsExactly(PUBLIC);
    }

    private final OwnerBanRepository bans = mock(OwnerBanRepository.class);
    private final FriendshipClient friendshipClient = mock(FriendshipClient.class);
    private final StoryAudience audience = new StoryAudience(bans, friendshipClient);

    private static final Relationship STRANGER = new Relationship(false, false, false, false);
    private static final Relationship PRIVATE_STRANGER = new Relationship(false, true, false, false);
    private static final Relationship PRIVATE_FOLLOWED = new Relationship(false, true, true, false);
    private static final Relationship FRIEND = new Relationship(false, false, true, true);

    @Test
    void anonymousViewerIsStillAskedWhetherTheOwnerIsPrivate() {
        when(friendshipClient.relationships(null, List.of(1L, 2L))).thenReturn(Map.of(1L, STRANGER, 2L, PRIVATE_STRANGER));

        Map<Long, Set<StoryVisibility>> resolved = audience.resolve(null, List.of(1L, 2L));

        assertThat(resolved.get(1L)).containsExactly(PUBLIC);
        assertThat(resolved.get(2L)).isEmpty();
    }

    @Test
    void manyOwnersCostOneRelationshipsCall() {
        List<Long> owners = LongStream.rangeClosed(1, 40).boxed().toList();
        when(friendshipClient.relationships(eq(99L), any()))
                .thenReturn(owners.stream().collect(Collectors.toMap(Function.identity(), owner -> STRANGER)));

        Map<Long, Set<StoryVisibility>> resolved = audience.resolve(99L, owners);

        assertThat(resolved).hasSize(40);
        assertThat(resolved.values()).allSatisfy(visible -> assertThat(visible).containsExactly(PUBLIC));
        verify(friendshipClient, times(1)).relationships(99L, owners);
        verifyNoMoreInteractions(friendshipClient);
        verify(bans, times(1)).findHiddenAmong(any(), any());
    }

    @Test
    void privacyFollowAndFriendshipComeFromOneCallForTheOwnersNotAlreadyRuledOut() {
        when(bans.findHiddenAmong(any(), any())).thenReturn(List.of(new OwnerBan(3L, OwnerBan.PERMANENT, Instant.now())));
        when(friendshipClient.relationships(9L, List.of(1L, 2L, 4L)))
                .thenReturn(Map.of(1L, STRANGER, 2L, PRIVATE_FOLLOWED, 4L, FRIEND));

        Map<Long, Set<StoryVisibility>> resolved = audience.resolve(9L, List.of(1L, 2L, 3L, 4L, 9L));

        assertThat(resolved.get(1L)).containsExactly(PUBLIC);
        assertThat(resolved.get(2L)).containsExactly(PUBLIC);
        assertThat(resolved.get(3L)).isEmpty();
        assertThat(resolved.get(4L)).containsExactlyInAnyOrder(PUBLIC, FRIENDS);
        assertThat(resolved.get(9L)).containsExactlyInAnyOrder(PUBLIC, FRIENDS, PRIVATE);
        // The viewer and the banned owner are not asked about: exactly the other three, in one call.
        verify(friendshipClient).relationships(9L, List.of(1L, 2L, 4L));
        verifyNoMoreInteractions(friendshipClient);
    }

    @Test
    void aStrangerToAPrivateOwnerSeesNothing() {
        when(friendshipClient.relationships(9L, List.of(1L))).thenReturn(Map.of(1L, PRIVATE_STRANGER));

        assertThat(audience.resolve(9L, List.of(1L)).get(1L)).isEmpty();
    }

    @Test
    void aBlockedOwnerSeesNothingWhateverElseIsTrueOfThem() {
        when(friendshipClient.relationships(9L, List.of(1L, 2L)))
                .thenReturn(Map.of(1L, new Relationship(true, false, true, true), 2L, STRANGER));

        Map<Long, Set<StoryVisibility>> resolved = audience.resolve(9L, List.of(1L, 2L));

        assertThat(resolved.get(1L)).isEmpty();
        assertThat(resolved.get(2L)).containsExactly(PUBLIC);
    }

    /** The client answers for every owner it is asked about; if it ever did not, the owner is hidden. */
    @Test
    void anOwnerNobodyAnsweredForSeesNothing() {
        when(friendshipClient.relationships(9L, List.of(1L))).thenReturn(Map.of());

        assertThat(audience.resolve(9L, List.of(1L)).get(1L)).isEmpty();
    }

    @Test
    void noCallWhenEveryOwnerIsHiddenOrTheViewer() {
        when(bans.findHiddenAmong(any(), any())).thenReturn(List.of(new OwnerBan(3L, OwnerBan.PERMANENT, Instant.now())));

        Map<Long, Set<StoryVisibility>> resolved = audience.resolve(9L, List.of(3L, 9L));

        assertThat(resolved.get(3L)).isEmpty();
        assertThat(resolved.get(9L)).containsExactlyInAnyOrder(PUBLIC, FRIENDS, PRIVATE);
        verifyNoInteractions(friendshipClient);
    }

    @Test
    void noOwnersAsksNobodyAnything() {
        assertThat(audience.resolve(9L, List.of())).isEmpty();

        verifyNoInteractions(bans, friendshipClient);
    }

    @Test
    void anOwnerIsHiddenWhileBannedOrClosed() {
        when(bans.findHiddenAmong(eq(List.of(3L)), any())).thenReturn(List.of(new OwnerBan(3L, OwnerBan.PERMANENT, Instant.now())));

        assertThat(audience.isHidden(3L)).isTrue();
        assertThat(audience.isHidden(4L)).isFalse();
        verifyNoInteractions(friendshipClient);
    }
}
