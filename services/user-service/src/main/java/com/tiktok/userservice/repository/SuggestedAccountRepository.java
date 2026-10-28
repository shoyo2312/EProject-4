package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserProfile;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The three tiers of "people you may know", as native SQL: each one is a different walk of the
 * follow graph, and all of them end in the same exclusion list, which lives in {@link #SUGGESTIBLE}
 * so the tiers cannot disagree about who may be offered.
 *
 * <p>A plain {@link Repository}: nothing here saves or reads a {@link UserProfile} as an entity.
 */
public interface SuggestedAccountRepository extends Repository<UserProfile, Long> {

    /**
     * Who may be offered to {@code :viewerId}. The candidate's {@code user_profiles} row is aliased
     * {@code c}. Excluded: the viewer, anyone the viewer follows or has a live follow request to,
     * anyone across a live block in either direction, anyone the viewer muted, and closed or deleted
     * profiles. Every edge predicate carries {@code deleted_at IS NULL} so the partial indexes serve
     * it.
     */
    String SUGGESTIBLE = """
            c.deleted_at IS NULL AND c.deactivated_at IS NULL AND c.user_id <> :viewerId
            AND NOT EXISTS (SELECT 1 FROM user_follows mine WHERE mine.follower_id = :viewerId
                            AND mine.following_id = c.user_id AND mine.deleted_at IS NULL)
            AND NOT EXISTS (SELECT 1 FROM user_follow_requests r WHERE r.requester_id = :viewerId
                            AND r.target_id = c.user_id AND r.deleted_at IS NULL)
            AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.deleted_at IS NULL
                            AND ((b.blocker_id = :viewerId AND b.blocked_id = c.user_id)
                              OR (b.blocked_id = :viewerId AND b.blocker_id = c.user_id)))
            AND NOT EXISTS (SELECT 1 FROM user_mutes m WHERE m.muter_id = :viewerId
                            AND m.muted_id = c.user_id AND m.deleted_at IS NULL)
            """;

    /** A candidate reached through one of the viewer's followings ({@code viaId}). */
    interface FollowedByCandidate {
        Long getUserId();

        Long getViaId();
    }

    /** Tier 1: live followers of the viewer whom the viewer does not follow back, newest first. */
    @Query(nativeQuery = true, value = """
            SELECT f.follower_id
            FROM user_follows f
            JOIN user_profiles c ON c.user_id = f.follower_id
            WHERE f.following_id = :viewerId AND f.deleted_at IS NULL AND
            """ + SUGGESTIBLE + """
            ORDER BY f.created_at DESC, f.id DESC
            LIMIT :limit
            """)
    List<Long> findFollowersNotFollowedBack(@Param("viewerId") long viewerId, @Param("limit") int limit);

    /**
     * Tier 2: accounts followed by the accounts the viewer follows, ranked by how many of those
     * followings follow them, then by follower count, then by id. Only a live, open profile counts
     * as a source — "Followed by" must not name an account that answers 404 to everyone. The source
     * reported is the one the viewer followed most recently.
     *
     * <p>ponytail: walks the viewer's followings' followings (F × M rows) on
     * idx_user_follows_follower_id; cap the followings considered to the most recent N once accounts
     * follow tens of thousands.
     */
    @Query(nativeQuery = true, value = """
            SELECT f2.following_id AS "userId",
                   (array_agg(f1.following_id ORDER BY f1.created_at DESC, f1.id DESC))[1] AS "viaId"
            FROM user_follows f1
            JOIN user_profiles src ON src.user_id = f1.following_id
                 AND src.deleted_at IS NULL AND src.deactivated_at IS NULL
            JOIN user_follows f2 ON f2.follower_id = f1.following_id AND f2.deleted_at IS NULL
            JOIN user_profiles c ON c.user_id = f2.following_id
            WHERE f1.follower_id = :viewerId AND f1.deleted_at IS NULL AND
            """ + SUGGESTIBLE + """
            GROUP BY f2.following_id, c.follower_count
            ORDER BY count(*) DESC, c.follower_count DESC, f2.following_id
            LIMIT :limit
            """)
    List<FollowedByCandidate> findFollowedByFollowings(@Param("viewerId") long viewerId, @Param("limit") int limit);

    /**
     * Tier 3: the most followed accounts the viewer may be offered.
     *
     * <p>ponytail: sorts every profile, no index; an index on follower_count is rewritten on every
     * follow, so add one only when this shows up in a profile.
     */
    @Query(nativeQuery = true, value = """
            SELECT c.user_id
            FROM user_profiles c
            WHERE
            """ + SUGGESTIBLE + """
            ORDER BY c.follower_count DESC, c.user_id
            LIMIT :limit
            """)
    List<Long> findMostFollowed(@Param("viewerId") long viewerId, @Param("limit") int limit);
}
