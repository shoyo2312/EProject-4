package com.tiktok.storyservice.service;

import com.tiktok.storyservice.client.BlockClient;
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
    private final BlockClient blockClient = mock(BlockClient.class);
    private final StoryAudience audience = new StoryAudience(bans, friendshipClient, blockClient);

    private static final Relationship STRANGER = new Relationship(false, false, false);

    @Test
    void anonymousViewerIsAskedOnlyWhetherTheOwnerIsPrivate() {
        when(friendshipClient.relationship(null, 1L)).thenReturn(STRANGER);
        when(friendshipClient.relationship(null, 2L)).thenReturn(new Relationship(false, false, true));

        Map<Long, Set<StoryVisibility>> resolved = audience.resolve(null, List.of(1L, 2L));

        assertThat(resolved.get(1L)).containsExactly(PUBLIC);
        assertThat(resolved.get(2L)).isEmpty();
        verifyNoInteractions(blockClient);
    }

    @Test
    void privacyFollowAndFriendshipComeFromUserServicePerOwner() {
        when(bans.findHiddenAmong(any(), any())).thenReturn(List.of(new OwnerBan(3L, OwnerBan.PERMANENT, Instant.now())));
        when(friendshipClient.relationship(9L, 1L)).thenReturn(STRANGER);
        when(friendshipClient.relationship(9L, 2L)).thenReturn(new Relationship(false, true, true));
        when(friendshipClient.relationship(9L, 4L)).thenReturn(new Relationship(true, true, false));

        Map<Long, Set<StoryVisibility>> resolved = audience.resolve(9L, List.of(1L, 2L, 3L, 4L, 9L));

        assertThat(resolved.get(1L)).containsExactly(PUBLIC);
        assertThat(resolved.get(2L)).containsExactly(PUBLIC);
        assertThat(resolved.get(3L)).isEmpty();
        assertThat(resolved.get(4L)).containsExactlyInAnyOrder(PUBLIC, FRIENDS);
        assertThat(resolved.get(9L)).containsExactlyInAnyOrder(PUBLIC, FRIENDS, PRIVATE);
        verify(friendshipClient, never()).relationship(9L, 3L);
        verify(friendshipClient, never()).relationship(9L, 9L);
    }

    @Test
    void aBlockedOwnerIsNotAskedAboutFurther() {
        when(blockClient.isBlockedBetween(9L, 1L)).thenReturn(true);

        assertThat(audience.resolve(9L, List.of(1L)).get(1L)).isEmpty();
        verifyNoInteractions(friendshipClient);
    }

    @Test
    void anOwnerIsHiddenWhileBannedOrClosed() {
        when(bans.findHiddenAmong(eq(List.of(3L)), any())).thenReturn(List.of(new OwnerBan(3L, OwnerBan.PERMANENT, Instant.now())));

        assertThat(audience.isHidden(3L)).isTrue();
        assertThat(audience.isHidden(4L)).isFalse();
        verifyNoInteractions(friendshipClient, blockClient);
    }
}
