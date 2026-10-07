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

    /**
     * Handle lookup, for URLs that address an account by name rather than by id.
     *
     * <p>{@code findFirst} because the column carries no unique constraint — it is a copy of
     * auth-service's handle, which owns uniqueness — and case-insensitive because a handle in a
     * URL is typed by hand.
     *
     * <p>ponytail: sequential scan. The only index on username is the GIN trigram one V8 built
     * for search, which does not serve equality; add a {@code lower(username)} btree if the
     * profile table ever grows past "a class project".
     */
    Optional<UserProfile> findFirstByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    /**
     * Takes {@code username} off every live profile but {@code userId}'s, ahead of giving it to
     * that one.
     *
     * <p>auth-service lets one account hold a handle at a time, so another profile still showing it
     * is a copy whose own rename has not arrived yet: a registration and a rename travel on
     * different topics and land in either order. Left alone, two profiles would answer to one
     * handle and {@link #findFirstByUsernameIgnoreCaseAndDeletedAtIsNull} would return whichever
     * row Postgres reads first. Cleared, the stale profile has no handle until its rename sets the
     * new one.
     *
     * <p>The version moves for the reason {@link #updatePrivateAccount} gives: a {@code PATCH /me}
     * on the stale profile that flushed after this would otherwise write the handle straight back.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.username = null, p.version = p.version + 1 "
            + "where lower(p.username) = lower(:username) and p.userId <> :userId and p.deletedAt is null")
    void releaseUsername(@Param("username") String username, @Param("userId") Long userId);

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
     *
     * <p>{@code query} must arrive LIKE-escaped (see {@code UserProfileServiceImpl.search}):
     * unescaped, {@code _} matches any character and {@code q=_} listed every profile.
     *
     * <p>Blocks are excluded in the query rather than filtered out of the page afterwards, because
     * a page filtered afterwards is short and its total still counts rows the caller can never
     * reach — a client paging towards that total never arrives. Here the two agree.
     */
    @Query("select p from UserProfile p where p.deletedAt is null "
            + "and (lower(p.displayName) like lower(concat('%', :query, '%')) escape '\\' "
            + "or lower(p.username) like lower(concat('%', :query, '%')) escape '\\') "
            + "and not exists (select 1 from UserBlock b where b.deletedAt is null "
            + "and ((b.blockerId = :viewerId and b.blockedId = p.userId) "
            + "or (b.blockedId = :viewerId and b.blockerId = p.userId))) "
            + "order by p.followerCount desc, p.userId asc")
    Page<UserProfile> search(@Param("viewerId") Long viewerId,
                             @Param("query") String query,
                             Pageable pageable);

    /**
     * The same match and order as {@link #search}, ids only and with no viewer: search-service
     * asks it so a video search for "vmh" also finds the videos of vmh26122004, and it has no
     * idea who is searching, so there are no blocks to apply. No count query either — the caller
     * wants the best few, not a page.
     */
    @Query("select p.userId from UserProfile p where p.deletedAt is null "
            + "and (lower(p.displayName) like lower(concat('%', :query, '%')) escape '\\' "
            + "or lower(p.username) like lower(concat('%', :query, '%')) escape '\\') "
            + "order by p.followerCount desc, p.userId asc")
    List<Long> searchIds(@Param("query") String query, Pageable pageable);

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

    /**
     * Sets the flag only where it differs, so the row count says whether anything changed: two
     * PATCHes racing to the same value update one row between them, and only that one announces.
     *
     * <p>The version moves too. Hibernate writes every column of a dirty profile, so a
     * {@code PATCH /me} that read the row before this and flushed after would otherwise write the
     * old flag straight back — silently, since the version it checks would still match.
     *
     * @return 1 when the flag changed, 0 when it already had that value or there is no profile
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserProfile p set p.privateAccount = :privateAccount, p.version = p.version + 1 "
            + "where p.userId = :userId and p.deletedAt is null and p.privateAccount <> :privateAccount")
    int updatePrivateAccount(@Param("userId") Long userId, @Param("privateAccount") boolean privateAccount);

    /**
     * Off the profile, where every viewer reads it. A missing or deleted profile answers false,
     * which is right: nothing can make an account private before its profile row exists.
     */
    boolean existsByUserIdAndDeletedAtIsNullAndPrivateAccountTrue(Long userId);

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
