package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

    Optional<UserFollow> findByFollowerIdAndFollowingIdAndDeletedAtIsNull(Long followerId, Long followingId);

    long countByFollowingIdAndDeletedAtIsNull(Long followingId);

    long countByFollowerIdAndDeletedAtIsNull(Long followerId);

    Page<UserFollow> findByFollowingIdAndDeletedAtIsNull(Long followingId, Pageable pageable);

    Page<UserFollow> findByFollowerIdAndDeletedAtIsNull(Long followerId, Pageable pageable);

    @Query("select f.followingId as userId, count(f) as count from UserFollow f " +
            "where f.followingId in :userIds and f.deletedAt is null group by f.followingId")
    List<FollowCountProjection> countFollowersByUserIdIn(@Param("userIds") List<Long> userIds);

    @Query("select f.followerId as userId, count(f) as count from UserFollow f " +
            "where f.followerId in :userIds and f.deletedAt is null group by f.followerId")
    List<FollowCountProjection> countFollowingByUserIdIn(@Param("userIds") List<Long> userIds);
}
