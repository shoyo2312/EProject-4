package com.tiktok.userservice.service;

import com.tiktok.userservice.config.MinioProperties;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.InvalidAvatarException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito rather than {@code @SpringBootTest}: what is worth pinning here is the refusals
 * and the URL, none of which needs a context or a container.
 */
class AvatarUploadServiceTest {

    private static final Long USER_ID = 42L;

    private MinioClient minioClient;
    private UserProfileService userProfileService;
    private AvatarUploadService service;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        userProfileService = mock(UserProfileService.class);
        service = new AvatarUploadService(
                minioClient,
                new MinioProperties("http://localhost:9000", "key", "secret", "video-media"),
                userProfileService);
        ReflectionTestUtils.setField(service, "maxBytes", 1_000L);
    }

    @Test
    void storesTheFileAndPointsTheProfileAtACacheBustedUrl() throws Exception {
        when(userProfileService.replaceOwnAvatarUrl(eq(USER_ID), anyString()))
                .thenAnswer(invocation -> profileWith(invocation.getArgument(1)));

        UserProfileResponse response = service.replaceOwnAvatar(USER_ID, image("image/png", 500));

        ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(put.capture());
        assertThat(put.getValue().bucket()).isEqualTo("video-media");
        assertThat(put.getValue().object()).isEqualTo("avatars/42.jpg");
        assertThat(put.getValue().contentType()).isEqualTo("image/png");

        assertThat(response.avatarUrl())
                .startsWith("http://localhost:9000/video-media/avatars/42.jpg?v=")
                // Without the query the second upload of a fixed key is served from cache.
                .matches(".*\\?v=\\d+$");
    }

    @Test
    void rejectsATypeBrowsersWouldNotRenderAsAPicture() throws Exception {
        assertThatThrownBy(() -> service.replaceOwnAvatar(USER_ID, image("image/svg+xml", 100)))
                .isInstanceOf(InvalidAvatarException.class);

        verify(minioClient, never()).putObject(any());
    }

    @Test
    void rejectsAFileOverTheBudget() throws Exception {
        assertThatThrownBy(() -> service.replaceOwnAvatar(USER_ID, image("image/jpeg", 1_001)))
                .isInstanceOf(InvalidAvatarException.class);

        verify(minioClient, never()).putObject(any());
    }

    @Test
    void rejectsAnEmptyPart() throws Exception {
        assertThatThrownBy(() -> service.replaceOwnAvatar(USER_ID, image("image/jpeg", 0)))
                .isInstanceOf(InvalidAvatarException.class);

        verify(minioClient, never()).putObject(any());
    }

    private static MockMultipartFile image(String contentType, int bytes) {
        return new MockMultipartFile("file", "photo", contentType, new byte[bytes]);
    }

    private static UserProfileResponse profileWith(String avatarUrl) {
        return new UserProfileResponse(USER_ID, "handle", "Name", null, avatarUrl, 0, 0);
    }
}
