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

import java.time.Instant;

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
     * The account's handle, copied from the registration event and then from every
     * {@code UsernameChangedEvent}. Null for profiles created before this field existed — see V8
     * for why those were not backfilled. auth-service owns the handle and its uniqueness; this copy
     * follows asynchronously, so two profiles can briefly share one while a rename and a claim of
     * the name it freed are both in flight.
     */
    @Column(name = "username")
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /**
     * When the owner last changed {@link #displayName}; null until they first do. The name a
     * profile starts with is the handle, which the owner never chose, so it does not start the 7
     * days — see {@code DisplayNameCooldown}.
     */
    @Column(name = "display_name_changed_at")
    private Instant displayNameChangedAt;

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

    public void changeDisplayName(String newDisplayName, Instant at) {
        this.displayName = newDisplayName;
        this.displayNameChangedAt = at;
    }

    public void changeUsername(String newUsername) {
        this.username = newUsername;
    }

    /**
     * Partial update of the two optional fields: null leaves the current value alone, matching the
     * PATCH semantics of UpdateProfileRequest, and a blank string clears it — the only way a client
     * can erase a bio or avatar, since null already means "not sent". The display name is not
     * here because changing it is rate-limited: see {@link #changeDisplayName}.
     */
    public void updateProfile(String bio, String avatarUrl) {
        if (bio != null) {
            this.bio = bio.isBlank() ? null : bio;
        }
        if (avatarUrl != null) {
            this.avatarUrl = avatarUrl.isBlank() ? null : avatarUrl;
        }
    }
}
