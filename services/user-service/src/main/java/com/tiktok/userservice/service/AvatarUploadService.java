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

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

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

    /** Enough of the file to read every signature below; WebP's is the longest at 12 bytes. */
    private static final int SIGNATURE_BYTES = 12;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final UserProfileService userProfileService;

    /** Matches the client-side check and the multipart limit in application.yml. */
    @Value("${app.avatar.max-bytes:5242880}")
    private final long maxBytes;

    @SneakyThrows
    public UserProfileResponse replaceOwnAvatar(Long userId, MultipartFile file) {
        String contentType = requireSupported(file);

        // One key per user, overwritten in place — the same key media-worker mirrors a social
        // avatar to (MediaKeys.avatar). Sharing it is what stops the two paths from leaving a user
        // with two pictures and no rule about which wins; the mirror skips a key that already
        // exists, so a picture uploaded here is never replaced by a provider's.
        //
        // No extension: the key is fixed while the format is whatever was uploaded, so any
        // extension here is a claim about the bytes that is wrong as often as it is right, and
        // anything keying off it (CDN rules, the mirror writing the same key) is misled. The
        // content type travels on the object instead, which is what browsers actually read.
        String key = "avatars/%d".formatted(userId);

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

        // Decided by the bytes, never by the declared type or the file name: both are chosen by the
        // sender, and the object is served straight to browsers from a public prefix under the
        // type stored here. An HTML page declared as image/png used to go through.
        byte[] head;
        try (InputStream body = file.getInputStream()) {
            head = body.readNBytes(SIGNATURE_BYTES);
        } catch (IOException e) {
            throw new InvalidAvatarException("The image could not be read");
        }
        return sniff(head).orElseThrow(() -> new InvalidAvatarException("The image must be a JPEG, PNG, or WebP"));
    }

    /**
     * JPEG, PNG and WebP only: what a browser will render, and nothing that renders as markup. An
     * SVG is an image to a picker and a script host to a browser, so it has no signature here.
     */
    private static Optional<String> sniff(byte[] head) {
        if (startsWith(head, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(head, 0, 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n')) {
            return Optional.of("image/png");
        }
        if (startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int offset, int... signature) {
        if (bytes.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
