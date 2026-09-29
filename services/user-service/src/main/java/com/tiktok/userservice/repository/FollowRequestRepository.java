package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.FollowRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FollowRequestRepository extends JpaRepository<FollowRequest, Long> {

    Optional<FollowRequest> findByRequesterIdAndTargetIdAndDeletedAtIsNull(Long requesterId, Long targetId);

    /** The owner's list, newest first; the Snowflake id breaks a tie inside one microsecond. */
    Page<FollowRequest> findByTargetIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(Long targetId, Pageable pageable);

    /** Every pending request of an account going public. */
    List<FollowRequest> findByTargetIdAndDeletedAtIsNull(Long targetId);
}
