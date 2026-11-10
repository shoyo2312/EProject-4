package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserBlock;
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

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    interface BlockState {
        Long getUserId();
        boolean getBlockedByViewer();
        boolean getBlockedViewer();
        long getVersion();
    }

    // Deliberately includes deleted rows: an unblock must have a newer version than its block.
    // The CASE is computed once in a subquery: PostgreSQL does not see a SELECT expression and a
    // GROUP BY expression as the same when each carries its own bind parameter.
    @Query(value = """
            select other_id as "userId",
                   bool_or(mine and deleted_at is null) as "blockedByViewer",
                   bool_or(not mine and deleted_at is null) as "blockedViewer",
                   floor(extract(epoch from max(greatest(created_at, deleted_at))) * 1000)::bigint as version
            from (select case when blocker_id = :viewerId then blocked_id else blocker_id end as other_id,
                         blocker_id = :viewerId as mine, created_at, deleted_at
                  from user_blocks
                  where (blocker_id = :viewerId and blocked_id in (:otherIds))
                     or (blocked_id = :viewerId and blocker_id in (:otherIds))) pair
            group by other_id
            """, nativeQuery = true)
    List<BlockState> findStates(@Param("viewerId") Long viewerId, @Param("otherIds") Collection<Long> otherIds);

    // BaseEntity timestamps use the wall clock. Clamp to previous pair version + 1 ms under the
    // pair lock so rapid block/unblock/reblock (or clock rollback) cannot reuse a client version.
    @Modifying(flushAutomatically = true)
    @Query(value = "update user_blocks set created_at = greatest(created_at, :at), "
            + "updated_at = greatest(updated_at, :at) where id = :id", nativeQuery = true)
    void advanceCreatedAt(@Param("id") Long id, @Param("at") Instant at);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update user_blocks set deleted_at = :at, updated_at = :at, version = version + 1 "
            + "where id = :id and deleted_at is null", nativeQuery = true)
    void endBlock(@Param("id") Long id, @Param("at") Instant at);

    Optional<UserBlock> findByBlockerIdAndBlockedIdAndDeletedAtIsNull(Long blockerId, Long blockedId);

    Page<UserBlock> findByBlockerIdAndDeletedAtIsNull(Long blockerId, Pageable pageable);

    @Query("select count(b) > 0 from UserBlock b where b.deletedAt is null and " +
            "((b.blockerId = :userA and b.blockedId = :userB) or (b.blockerId = :userB and b.blockedId = :userA))")
    boolean existsBlockBetween(@Param("userA") Long userA, @Param("userB") Long userB);

    /**
     * {@link #existsBlockBetween} for a whole page of ids at once, answering with the subset of
     * {@code userIds} that sits on either side of a block with {@code viewerId}. One query, because
     * the batch endpoint exists precisely to stop a page of ids costing a query each — calling
     * existsBlockBetween in a loop would put the N+1 straight back in.
     */
    @Query("select case when b.blockerId = :viewerId then b.blockedId else b.blockerId end " +
            "from UserBlock b where b.deletedAt is null and " +
            "((b.blockerId = :viewerId and b.blockedId in :userIds) " +
            "or (b.blockedId = :viewerId and b.blockerId in :userIds))")
    List<Long> findBlockedIdsAmong(@Param("viewerId") Long viewerId, @Param("userIds") Collection<Long> userIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update UserBlock b set b.deletedAt = :at "
            + "where (b.blockerId = :userId or b.blockedId = :userId) and b.deletedAt is null")
    int softDeleteAllOf(@Param("userId") Long userId, @Param("at") Instant at);
}
