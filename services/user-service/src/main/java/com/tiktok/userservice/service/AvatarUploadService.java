package com.tiktok.userservice.service;

import com.tiktok.userservice.config.MinioProperties;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.InvalidAvatarException;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Receives the picture a user picked in the client and stores it as their avatar.
 *
 * <p>This exists because {@code PATCH /users/me} cannot be the whole answer: {@code avatarUrl} is
 * validated against our own CDN allow-list, so a client has no URL it is allowed to send until
 * something has already put the bytes somewhere. That something is here — the file arrives, this
 * service writes it, and the profile is pointed at the URL <em>it</em> chose. A client never names
 * a storage location, which is exactly the property the allow-list protects.
 *
 * <p>The upload deliberately happens outside the profile transaction: writing to object storage is
 * a network round trip, and holding a row lock across it turns every slow upload into contention
 * on the profile table. The order is store-then-update, so a failure between the two leaves an
 * orphan object rather than a profile pointing at bytes that were never written.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarUploadService {

    /**
     * What a browser will actually render, and nothing that renders as markup. An SVG is an image
     * to a picker and a script host to a browser, so it is not on this list.
     */
    private static final List<String> ALLOWED_CONTENT_TYPES =
            List.of("image/jpeg", "image/png", "image/webp");

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final UserProfileService userProfileService;

    /** Matches the client-side check and the multipart limit in application.yml. */
    @Value("${app.avatar.max-bytes:5242880}")
    private long maxBytes;

    @SneakyThrows
    public UserProfileResponse replaceOwnAvatar(Long userId, MultipartFile file) {
        String contentType = requireSupported(file);

        // One key per user, overwritten in place — the same key media-worker mirrors a social
        // avatar to. Sharing it is what stops the two paths from leaving a user with two pictures
        // and no rule about which wins; the mirror skips a key that already exists, so a picture
        // uploaded here is never replaced by a provider's.
        String key = "avatars/%d.jpg".formatted(userId);

        try (InputStream body = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(key)
                    .contentType(contentType)
                    .stream(body, file.getSize(), -1)
                    .build());
        }

        /*
         * The `v` is not decoration: the key is fixed, so without it the stored URL after a second
         * upload is byte-for-byte the one already in every browser cache and CDN edge, and the
         * user sees their old picture for as long as that cache lives. Changing the URL is the
         * only signal those layers read.
         */
        String url = "%s/%s/%s?v=%d".formatted(
                minioProperties.endpoint(), minioProperties.bucket(), key, Instant.now().getEpochSecond());

        log.info("Stored a new avatar for user {} ({} bytes, {})", userId, file.getSize(), contentType);
        return userProfileService.replaceOwnAvatarUrl(userId, url);
    }

    private String requireSupported(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidAvatarException("No image was uploaded");
        }
        // Checked here as well as by the multipart limit: the limit rejects the request before the
        // controller runs, which is the coarse guard, while this one is the rule the endpoint
        // states and can be tightened without touching servlet configuration.
        if (file.getSize() > maxBytes) {
            throw new InvalidAvatarException("The image must be %d bytes or smaller".formatted(maxBytes));
        }

        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new InvalidAvatarException("The image must be a JPEG, PNG, or WebP");
        }
        // Stored as the type declared here, never as one guessed from the file name: the object is
        // served straight to browsers, and a name is the one part of an upload the sender chooses.
        return contentType;
    }
}
