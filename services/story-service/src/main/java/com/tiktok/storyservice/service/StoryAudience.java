package com.tiktok.storyservice.service;

import com.tiktok.storyservice.client.BlockClient;
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
    private final BlockClient blockClient;

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

    /**
     * One ban query for every owner, then per owner a block check (signed-in viewers only) and one
     * relationship lookup — privacy, follow and friendship together. Hidden and blocked owners are
     * not asked about further.
     *
     * <p>ponytail: one or two user-service calls per owner, in sequence — fine for a tray of
     * followed accounts with live stories; a batch relationship endpoint is the upgrade.
     */
    public Map<Long, Set<StoryVisibility>> resolve(Long viewerId, Collection<Long> ownerIds) {
        Set<Long> owners = new LinkedHashSet<>(ownerIds);
        Map<Long, Set<StoryVisibility>> result = new HashMap<>();
        List<Long> others = owners.stream().filter(owner -> !owner.equals(viewerId)).toList();
        Set<Long> hidden = others.isEmpty() ? Set.of() : ownerBanRepository.findHiddenAmong(others, Instant.now()).stream()
                .map(OwnerBan::getUserId).collect(Collectors.toSet());

        for (Long owner : owners) {
            boolean self = owner.equals(viewerId);
            boolean isHidden = hidden.contains(owner);
            boolean blocked = !self && !isHidden && viewerId != null && blockClient.isBlockedBetween(viewerId, owner);
            Relationship relationship = self || isHidden || blocked
                    ? Relationship.UNKNOWN : friendshipClient.relationship(viewerId, owner);
            result.put(owner, visibilitiesFor(self, isHidden, blocked, relationship.privateAccount(),
                    relationship.following(), relationship.friends()));
        }
        return result;
    }
}
