package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserBlock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserBlockRepository extends JpaRepository<UserBlock, Long> {

    Optional<UserBlock> findByBlockerIdAndBlockedIdAndDeletedAtIsNull(Long blockerId, Long blockedId);

    Page<UserBlock> findByBlockerIdAndDeletedAtIsNull(Long blockerId, Pageable pageable);

    @Query("select count(b) > 0 from UserBlock b where b.deletedAt is null and " +
            "((b.blockerId = :userA and b.blockedId = :userB) or (b.blockerId = :userB and b.blockedId = :userA))")
    boolean existsBlockBetween(@Param("userA") Long userA, @Param("userB") Long userB);
}
