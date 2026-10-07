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
import org.hibernate.annotations.DynamicUpdate;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_profiles")
// An edit's UPDATE carries only the columns it changed. Hibernate's default writes every column,
// and the follower/following counters move by bulk updates that leave the version alone (see
// UserProfileRepository), so an edit that read the row before a follow and flushed after it wrote
// the old count back — past the version check, and permanently, since nothing recounts.
@DynamicUpdate
public class UserProfile extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * The account's handle, copied from the registration event. Null for profiles created before
     * this field existed — see V8 for why those were not backfilled. Never updated: auth-service
     * has no rename endpoint, so there is nothing here to keep in step with.
     */
    @Column(name = "username")
    private String username;

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

    /**
     * Only approved followers see this account's videos, lists and reposts. Switched only by
     * UserProfileRepository.updatePrivateAccount, which also moves the version — see there.
     */
    @Builder.Default
    @Column(name = "private_account", nullable = false)
    private boolean privateAccount = false;

    /**
     * Partial update: null arguments leave the current value alone, matching the PATCH semantics
     * of UpdateProfileRequest. Blank strings clear the optional fields — that is the only way a
     * client can erase a bio or avatar, since null already means "not sent".
     */
    public void updateProfile(String displayName, String bio, String avatarUrl) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (bio != null) {
            this.bio = bio.isBlank() ? null : bio;
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl.isBlank() ? null : avatarUrl;
        }
    }
}
