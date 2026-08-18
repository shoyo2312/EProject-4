package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

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
}
