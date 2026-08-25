package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByUserIdAndDeletedAtIsNull(Long userId);

    boolean existsByUserIdAndDeletedAtIsNull(Long userId);

    List<UserProfile> findByUserIdInAndDeletedAtIsNull(List<Long> userIds);

    /**
     * Profile search, over the handle and the display name at once.
     *
     * <p>{@code lower(...) like lower('%q%')} rather than a derived
     * {@code ContainingIgnoreCase}: the leading wildcard is what makes this a search rather than a
     * prefix lookup, and the two GIN trigram indexes in V8 are built on exactly this expression.
     * Written by hand so the query and the index cannot drift apart.
     *
     * <p>Ordered by follower count because the query is short and ambiguous by nature — several
     * accounts match "an" — and the one people mean is almost always the biggest. The userId
     * tiebreak keeps paging stable across two profiles with the same count.
     */
    @Query("select p from UserProfile p where p.deletedAt is null "
            + "and (lower(p.displayName) like lower(concat('%', :query, '%')) "
            + "or lower(p.username) like lower(concat('%', :query, '%'))) "
            + "order by p.followerCount desc, p.userId asc")
    Page<UserProfile> search(@Param("query") String query, Pageable pageable);

    /**
     * Points a profile at the copy media-worker made of its provider picture.
     *
     * <p>The WHERE clause is the whole decision, and it is made in the database rather than by
     * reading the row first: two sign-ins can announce the same avatar at once, and a
     * read-then-write would let the later one overwrite a picture the user chose in between.
     *
     * <p>Two states qualify and no others. An empty avatar — a profile that never had one, which
     * is every account created before any of this existed. And one still pointing at the very URL
     * that was copied, which is the profile seeded at signup. Anything else is a picture its owner
     * uploaded, and a copy of an old provider photo must never win against that.
     *
     * @return rows updated: 0 when the profile is gone or its avatar belongs to the user.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.avatarUrl = :avatarUrl "
            + "where p.userId = :userId and p.deletedAt is null "
            + "and (p.avatarUrl is null or p.avatarUrl = :sourceUrl)")
    int replaceProviderAvatar(@Param("userId") Long userId,
                              @Param("sourceUrl") String sourceUrl,
                              @Param("avatarUrl") String avatarUrl);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.followerCount = p.followerCount + 1 where p.userId = :userId")
    void incrementFollowerCount(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.followerCount = case when p.followerCount > 0 then p.followerCount - 1 else 0 end " +
            "where p.userId = :userId")
    void decrementFollowerCount(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.followingCount = p.followingCount + 1 where p.userId = :userId")
    void incrementFollowingCount(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.followingCount = case when p.followingCount > 0 then p.followingCount - 1 else 0 end " +
            "where p.userId = :userId")
    void decrementFollowingCount(@Param("userId") Long userId);
}
