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
        assertThat(put.getValue().object()).isEqualTo("avatars/42");
        assertThat(put.getValue().contentType()).isEqualTo("image/png");

        assertThat(response.avatarUrl())
                .startsWith("http://localhost:9000/video-media/avatars/42?v=")
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

    /**
     * The declared type the caller sent, over bytes that really are that format (or zeros for a
     * type with no signature here), so the tests about size and declared type stay about that.
     */
    private static MockMultipartFile image(String contentType, int bytes) {
        byte[] body = new byte[bytes];
        byte[] magic = switch (contentType) {
            case "image/png" -> PNG;
            case "image/jpeg" -> JPEG;
            case "image/webp" -> WEBP;
            default -> new byte[0];
        };
        System.arraycopy(magic, 0, body, 0, Math.min(magic.length, bytes));
        return new MockMultipartFile("file", "photo", contentType, body);
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    /**
     * The declared type is the one part of an upload the sender picks freely. An HTML page sent as
     * image/png was stored, and served from the public avatars/ prefix under that type — whatever
     * a browser or an intermediary sniffs it as, it is not a picture.
     */
    @Test
    void rejectsBytesThatAreNotAnImageWhateverTheDeclaredType() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.replaceOwnAvatar(USER_ID,
                new MockMultipartFile("file", "photo", "image/png", html)))
                .isInstanceOf(InvalidAvatarException.class);

        verify(minioClient, never()).putObject(any());
    }

    /** Stored as what the bytes are, so the Content-Type served never contradicts the file. */
    @Test
    void storesTheTypeTheBytesActuallyAre() throws Exception {
        when(userProfileService.replaceOwnAvatarUrl(eq(USER_ID), anyString()))
                .thenAnswer(invocation -> profileWith(invocation.getArgument(1)));
        MockMultipartFile pngLabelledJpeg = new MockMultipartFile("file", "photo", "image/jpeg",
                image("image/png", 100).getBytes());

        service.replaceOwnAvatar(USER_ID, pngLabelledJpeg);

        ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(put.capture());
        assertThat(put.getValue().contentType()).isEqualTo("image/png");
    }

    private static UserProfileResponse profileWith(String avatarUrl) {
        return new UserProfileResponse(USER_ID, "handle", "Name", null, avatarUrl, 0, 0);
    }
}
