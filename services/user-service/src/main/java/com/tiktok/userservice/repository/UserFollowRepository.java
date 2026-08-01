package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserFollow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

    Optional<UserFollow> findByFollowerIdAndFollowingIdAndDeletedAtIsNull(Long followerId, Long followingId);

    Page<UserFollow> findByFollowingIdAndDeletedAtIsNull(Long followingId, Pageable pageable);

    Page<UserFollow> findByFollowerIdAndDeletedAtIsNull(Long followerId, Pageable pageable);
}
