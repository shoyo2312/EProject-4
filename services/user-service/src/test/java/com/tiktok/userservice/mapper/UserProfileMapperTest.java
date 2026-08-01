package com.tiktok.userservice.mapper;

import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserProfile;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileMapperTest {

    private final UserProfileMapper mapper = Mappers.getMapper(UserProfileMapper.class);

    @Test
    void toResponse_mapsProfileFieldsAndIgnoresFollowCounts() {
        UserProfile profile = UserProfile.builder()
                .userId(42L)
                .displayName("Alice")
                .bio("Just here for the memes")
                .avatarUrl("https://cdn.example.com/avatar.png")
                .build();

        UserProfileResponse response = mapper.toResponse(profile);

        assertThat(response.userId()).isEqualTo(42L);
        assertThat(response.displayName()).isEqualTo("Alice");
        assertThat(response.bio()).isEqualTo("Just here for the memes");
        assertThat(response.avatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");
        // followerCount/followingCount are @Mapping(ignore = true): the service layer fills
        // these in separately from batched count queries, not from the entity itself.
        assertThat(response.followerCount()).isZero();
        assertThat(response.followingCount()).isZero();
    }

    @Test
    void toResponse_null_returnsNull() {
        assertThat(mapper.toResponse(null)).isNull();
    }
}
