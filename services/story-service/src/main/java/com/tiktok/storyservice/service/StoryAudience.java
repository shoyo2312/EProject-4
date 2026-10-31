package com.tiktok.storyservice.service;

import com.tiktok.storyservice.client.FriendshipClient;
import com.tiktok.storyservice.client.FriendshipClient.Relationship;
import com.tiktok.storyservice.entity.OwnerBan;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.repository.OwnerBanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which of an owner's stories a viewer may see, by visibility — the rules video-service applies to
 * videos (VideoServiceImpl.isVisibleTo), so a story is never visible to anyone the equivalent
 * video would be hidden from. Status, expiry and deletion are the caller's filter; this answers
 * only for the owner.
 */
@Component
@RequiredArgsConstructor
public class StoryAudience {

    private final OwnerBanRepository ownerBanRepository;
    private final FriendshipClient friendshipClient;

    /**
     * @param hidden       the owner is banned or closed their account
     * @param blocked      either of the two blocked the other
     * @param privateOwner the owner's account is private
     */
    static Set<StoryVisibility> visibilitiesFor(boolean self, boolean hidden, boolean blocked,
                                                 boolean privateOwner, boolean follows, boolean friends) {
        if (self) {
            return EnumSet.allOf(StoryVisibility.class);
        }
        if (hidden || blocked) {
            return EnumSet.noneOf(StoryVisibility.class);
        }
        if (friends) {
            return EnumSet.of(StoryVisibility.PUBLIC, StoryVisibility.FRIENDS);
        }
        if (privateOwner && !follows) {
            return EnumSet.noneOf(StoryVisibility.class);
        }
        return EnumSet.of(StoryVisibility.PUBLIC);
    }

    /** Whether this owner is banned or has closed their account — {@link #resolve}'s first check, for one owner. */
    public boolean isHidden(Long ownerId) {
        return !ownerBanRepository.findHiddenAmong(List.of(ownerId), Instant.now()).isEmpty();
    }

    /**
     * One ban query for every owner, then the relationships of the owners that are neither the
     * viewer nor hidden, one call per 100 of them — block, privacy, follow and friendship together,
     * not two calls per owner. A guest is asked too: whether an account is private matters most to
     * them. An owner nobody answered for shows nothing.
     */
    public Map<Long, Set<StoryVisibility>> resolve(Long viewerId, Collection<Long> ownerIds) {
        Set<Long> owners = new LinkedHashSet<>(ownerIds);
        List<Long> others = owners.stream().filter(owner -> !owner.equals(viewerId)).toList();
        Set<Long> hidden = others.isEmpty() ? Set.of() : ownerBanRepository.findHiddenAmong(others, Instant.now()).stream()
                .map(OwnerBan::getUserId).collect(Collectors.toSet());
        List<Long> toAsk = others.stream().filter(owner -> !hidden.contains(owner)).toList();
        Map<Long, Relationship> relationships = toAsk.isEmpty() ? Map.of() : friendshipClient.relationships(viewerId, toAsk);

        Map<Long, Set<StoryVisibility>> result = new HashMap<>();
        for (Long owner : owners) {
            // The viewer and hidden owners were not asked about: visibilitiesFor settles them before it looks at this.
            Relationship relationship = relationships.getOrDefault(owner, Relationship.UNKNOWN);
            result.put(owner, visibilitiesFor(owner.equals(viewerId), hidden.contains(owner), relationship.blocked(),
                    relationship.privateAccount(), relationship.following(), relationship.friends()));
        }
        return result;
    }
}
