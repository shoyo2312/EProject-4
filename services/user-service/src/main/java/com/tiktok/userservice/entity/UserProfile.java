package com.tiktok.userservice.entity;

import com.tiktok.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_profiles")
public class UserProfile extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "bio")
    private String bio;

    @Column(name = "avatar_url")
    private String avatarUrl;

    // Denormalized counters kept in sync by UserProfileRepository.increment/decrementFollower/FollowingCount,
    // so reads never need a COUNT(*) over user_follows (see FollowServiceImpl.follow/unfollow).
    @Builder.Default
    @Column(name = "follower_count", nullable = false)
    private long followerCount = 0L;

    @Builder.Default
    @Column(name = "following_count", nullable = false)
    private long followingCount = 0L;

    public void updateProfile(String displayName, String bio, String avatarUrl) {
        this.displayName = displayName;
        this.bio = bio;
        this.avatarUrl = avatarUrl;
    }
}
