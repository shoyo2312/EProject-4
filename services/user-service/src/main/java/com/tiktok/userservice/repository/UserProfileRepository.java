package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserProfile;
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
