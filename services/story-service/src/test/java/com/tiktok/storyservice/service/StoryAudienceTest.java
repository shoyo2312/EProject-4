package com.tiktok.storyservice.service;

import com.tiktok.storyservice.client.BlockClient;
import com.tiktok.storyservice.client.FollowClient;
import com.tiktok.storyservice.client.FriendshipClient;
import com.tiktok.storyservice.entity.OwnerBan;
import com.tiktok.storyservice.entity.OwnerPrivacy;
import com.tiktok.storyservice.repository.OwnerBanRepository;
import com.tiktok.storyservice.repository.OwnerPrivacyRepository;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
    private final OwnerPrivacyRepository privacy = mock(OwnerPrivacyRepository.class);
    private final FollowClient followClient = mock(FollowClient.class);
    private final FriendshipClient friendshipClient = mock(FriendshipClient.class);
    private final BlockClient blockClient = mock(BlockClient.class);
    private final StoryAudience audience = new StoryAudience(bans, privacy, followClient, friendshipClient, blockClient);

    @Test
    void anonymousViewerAsksUserServiceNothing() {
        when(privacy.findByUserIdInAndPrivateAccountTrue(any())).thenReturn(List.of(new OwnerPrivacy(2L, true, Instant.now())));

        Map<Long, Set<com.tiktok.storyservice.entity.StoryVisibility>> resolved = audience.resolve(null, List.of(1L, 2L));

        assertThat(resolved.get(1L)).containsExactly(PUBLIC);
        assertThat(resolved.get(2L)).isEmpty();
        verifyNoInteractions(followClient, friendshipClient, blockClient);
    }

    @Test
    void followIsAskedOnceForThePrivateOwnersOnly() {
        when(bans.findHiddenAmong(any(), any())).thenReturn(List.of(new OwnerBan(3L, OwnerBan.PERMANENT, Instant.now())));
        when(privacy.findByUserIdInAndPrivateAccountTrue(any())).thenReturn(List.of(new OwnerPrivacy(2L, true, Instant.now())));
        when(followClient.followedAmong(eq(9L), any())).thenReturn(Set.of(2L));

        Map<Long, Set<com.tiktok.storyservice.entity.StoryVisibility>> resolved = audience.resolve(9L, List.of(1L, 2L, 3L, 9L));

        assertThat(resolved.get(1L)).containsExactly(PUBLIC);
        assertThat(resolved.get(2L)).containsExactly(PUBLIC);
        assertThat(resolved.get(3L)).isEmpty();
        assertThat(resolved.get(9L)).containsExactlyInAnyOrder(PUBLIC, FRIENDS, PRIVATE);
        verify(followClient).followedAmong(9L, Set.of(2L));
        verify(friendshipClient, never()).areFriends(eq(3L), anyLong());
        verify(friendshipClient, never()).areFriends(eq(9L), anyLong());
    }
}
