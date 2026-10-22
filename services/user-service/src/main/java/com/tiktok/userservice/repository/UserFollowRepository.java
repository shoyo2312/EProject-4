package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

    Optional<UserFollow> findByFollowerIdAndFollowingIdAndDeletedAtIsNull(Long followerId, Long followingId);

    Page<UserFollow> findByFollowingIdAndDeletedAtIsNull(Long followingId, Pageable pageable);

    Page<UserFollow> findByFollowerIdAndDeletedAtIsNull(Long followerId, Pageable pageable);

    /**
     * The follower ids of {@code userId} that {@code viewerId} is allowed to see.
     *
     * <p>ProfileVisibilityGuard decides whether the list may be read at all and says nothing about
     * who is in it, so without this a user who blocked the viewer still showed up in any follower
     * list the two share. A block hides both sides from each other, and listing an entry is a read
     * of that entry.
     *
     * <p>Excluded in the query rather than filtered out of the page, for the same reason as
     * {@code UserProfileRepository.search}: a page filtered afterwards comes back short under a
     * total that still counts the hidden rows.
     */
    @Query("select f.followerId from UserFollow f where f.followingId = :userId and f.deletedAt is null "
            + "and not exists (select 1 from UserBlock b where b.deletedAt is null "
            + "and ((b.blockerId = :viewerId and b.blockedId = f.followerId) "
            + "or (b.blockedId = :viewerId and b.blockerId = f.followerId))) "
            + "and not exists (select 1 from UserProfile p where p.userId = f.followerId "
            + "and p.deactivatedAt is not null)")
    Page<Long> findFollowerIdsVisibleTo(@Param("viewerId") Long viewerId,
                                        @Param("userId") Long userId,
                                        Pageable pageable);

    /** {@link #findFollowerIdsVisibleTo} from the other side of the edge. */
    @Query("select f.followingId from UserFollow f where f.followerId = :userId and f.deletedAt is null "
            + "and not exists (select 1 from UserBlock b where b.deletedAt is null "
            + "and ((b.blockerId = :viewerId and b.blockedId = f.followingId) "
            + "or (b.blockedId = :viewerId and b.blockerId = f.followingId))) "
            + "and not exists (select 1 from UserProfile p where p.userId = f.followingId "
            + "and p.deactivatedAt is not null)")
    Page<Long> findFollowingIdsVisibleTo(@Param("viewerId") Long viewerId,
                                         @Param("userId") Long userId,
                                         Pageable pageable);

    /**
     * One page of who a new story of {@code ownerId} is announced to: the followers
     * {@link #findFollowerIdsVisibleTo} would show the owner (no block either way, no deactivated
     * profile) minus those who muted the owner, and with {@code friendsOnly} only the ones the owner
     * follows back. Keyset paging on the follower id, which {@code uq_user_follows_follower_following}
     * makes unique per owner, so follows changing between pages cannot shift them.
     *
     * <p>The mute check starts from the muter, the only way {@code user_mutes} is indexed (see
     * {@link UserMuteRepository}); starting from the muted side would scan the table.
     *
     * <p>ponytail: no index leads with {@code following_id} and then {@code follower_id}, so a page
     * walks the owner's followers and its cost grows with their number, not the page size. Fine for
     * thousands; a {@code (following_id, follower_id)} partial index makes it a range scan.
     */
    @Query("select f.followerId from UserFollow f where f.followingId = :ownerId and f.deletedAt is null "
            + "and f.followerId > :after "
            + "and not exists (select 1 from UserBlock b where b.deletedAt is null "
            + "and ((b.blockerId = :ownerId and b.blockedId = f.followerId) "
            + "or (b.blockedId = :ownerId and b.blockerId = f.followerId))) "
            + "and not exists (select 1 from UserProfile p where p.userId = f.followerId "
            + "and p.deactivatedAt is not null) "
            + "and not exists (select 1 from UserMute m where m.muterId = f.followerId "
            + "and m.mutedId = :ownerId and m.deletedAt is null) "
            + "and (:friendsOnly = false or exists (select 1 from UserFollow followBack "
            + "where followBack.followerId = :ownerId and followBack.followingId = f.followerId "
            + "and followBack.deletedAt is null)) "
            + "order by f.followerId")
    List<Long> findStoryAudience(@Param("ownerId") Long ownerId,
                                 @Param("friendsOnly") boolean friendsOnly,
                                 @Param("after") long after,
                                 Pageable limit);

    /**
     * The subset of {@code userIds} that {@code followerId} follows, in one query — the batch
     * follow check video-service asks once per page. No block filter: a block severs the edges.
     */
    @Query("select f.followingId from UserFollow f where f.followerId = :followerId "
            + "and f.followingId in :userIds and f.deletedAt is null")
    List<Long> findFollowingIdsAmong(@Param("followerId") Long followerId,
                                     @Param("userIds") Collection<Long> userIds);

    /** Everyone {@code userId} follows — for unwinding an account that is being deleted. */
    @Query("select f.followingId from UserFollow f where f.followerId = :userId and f.deletedAt is null")
    List<Long> findAllFollowingIds(@Param("userId") Long userId);

    /** Everyone following {@code userId} — for unwinding an account that is being deleted. */
    @Query("select f.followerId from UserFollow f where f.followingId = :userId and f.deletedAt is null")
    List<Long> findAllFollowerIds(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserFollow f set f.deletedAt = :at "
            + "where (f.followerId = :userId or f.followingId = :userId) and f.deletedAt is null")
    int softDeleteAllOf(@Param("userId") Long userId, @Param("at") Instant at);
}
