package com.tiktok.storyservice.service;

import com.tiktok.common.storage.ObjectKeys;
import com.tiktok.storyservice.config.MinioProperties;
import com.tiktok.storyservice.config.StoryUploadLimits;
import com.tiktok.storyservice.dto.response.StoryUploadUrlResponse;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.exception.ForeignUploadException;
import com.tiktok.storyservice.exception.UnsupportedUploadTypeException;
import com.tiktok.storyservice.exception.UploadNotFoundException;
import com.tiktok.storyservice.exception.UploadUrlUnavailableException;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Everything story-service does with object storage. Story media lives under {@code stories/},
 * which the bucket's anonymous-read policy does not cover and must never cover: a story is shown
 * only to the audience it was posted for, and only once moderation approved it, so the only way
 * to its bytes is a short-lived signed URL handed to someone allowed to see it at that moment.
 */
@Slf4j
@Component
public class StoryMedia {

    private static final String PREFIX = "stories";

    /** The last segment of a key as {@link #presignUpload} issues it: a Snowflake and an extension. */
    private static final Pattern FILE_NAME = Pattern.compile("[0-9]+\\.[a-z0-9]+");

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "video/mp4", "mp4",
            "video/webm", "webm");

    private static final Map<String, StoryMediaType> TYPES = Map.of(
            "jpg", StoryMediaType.IMAGE,
            "png", StoryMediaType.IMAGE,
            "webp", StoryMediaType.IMAGE,
            "mp4", StoryMediaType.VIDEO,
            "webm", StoryMediaType.VIDEO);

    /** Long enough to watch a tray of stories, short enough that a leaked link soon dies. */
    private static final Duration READ_EXPIRY = Duration.ofHours(1);

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final StoryUploadLimits limits;

    public StoryMedia(MinioClient minioClient, MinioProperties properties, StoryUploadLimits limits) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.limits = limits;
    }

    /**
     * Same mechanics as video-service's createUploadUrl: the policy pins the key and the content
     * type and signs a {@code content-length-range}, so MinIO itself refuses an oversize upload.
     */
    public StoryUploadUrlResponse presignUpload(Long userId, String contentType) {
        String type = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS.get(type);
        if (extension == null) {
            throw new UnsupportedUploadTypeException(contentType, EXTENSIONS.keySet());
        }
        long maxBytes = TYPES.get(extension) == StoryMediaType.IMAGE ? limits.imageMaxBytes() : limits.videoMaxBytes();

        String key = "%s/%d/%s.%s".formatted(PREFIX, userId, Story.newId(), extension);
        long expirySeconds = properties.urlExpiry().toSeconds();
        PostPolicy policy = new PostPolicy(properties.bucket(), ZonedDateTime.now().plusSeconds(expirySeconds));
        policy.addEqualsCondition("key", key);
        policy.addEqualsCondition("Content-Type", type);
        policy.addContentLengthRangeCondition(1L, maxBytes);

        Map<String, String> formFields;
        try {
            formFields = new HashMap<>(minioClient.getPresignedPostFormData(policy));
        } catch (Exception e) {
            log.error("Failed to presign a story upload for bucket {}", properties.bucket(), e);
            throw new UploadUrlUnavailableException(e);
        }
        formFields.put("key", key);
        formFields.put("Content-Type", type);

        return new StoryUploadUrlResponse("%s/%s".formatted(properties.uploadUrl(), properties.bucket()),
                formFields, "s3://%s/%s".formatted(properties.bucket(), key), expirySeconds);
    }

    /**
     * The key behind an upload URL, provided it has exactly the shape {@link #presignUpload} issued
     * to this requester. A prefix check alone would admit dot segments into another user's folder.
     */
    public String keyOf(Long requesterId, String uploadUrl) {
        String key = ObjectKeys.objectKey(uploadUrl, properties.bucket()).orElseThrow(ForeignUploadException::new);
        String ownPrefix = "%s/%d/".formatted(PREFIX, requesterId);
        if (!key.startsWith(ownPrefix) || !FILE_NAME.matcher(key.substring(ownPrefix.length())).matches()) {
            throw new ForeignUploadException();
        }
        return key;
    }

    public StoryMediaType typeOf(String key) {
        StoryMediaType type = TYPES.get(key.substring(key.lastIndexOf('.') + 1));
        if (type == null) {
            throw new UnsupportedUploadTypeException(key.substring(key.lastIndexOf('.')), TYPES.keySet());
        }
        return type;
    }

    /**
     * Copies an upload to the key the story is screened and served from,
     * {@code stories/{userId}/{storyId}/media.{ext}}. The upload form stays good for its whole
     * lifetime, so the upload key can be written again after moderation approved what was there;
     * the copy's extra segment is one no upload policy signs and {@link #keyOf} refuses. Made at
     * create, the copy also starts the bucket lifecycle's clock then, so an old upload cannot back a
     * story that outlives it.
     *
     * <p>The upload stays for the lifecycle rule to remove: this account cannot delete.
     */
    public String copyForStory(String uploadKey, String storyId) {
        String storyKey = "%s%s/media%s".formatted(uploadKey.substring(0, uploadKey.lastIndexOf('/') + 1), storyId,
                uploadKey.substring(uploadKey.lastIndexOf('.')));
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(storyKey)
                    .source(CopySource.builder().bucket(properties.bucket()).object(uploadKey).build())
                    .build());
            return storyKey;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new UploadNotFoundException();
            }
            throw new UploadUrlUnavailableException(e);
        } catch (Exception e) {
            throw new UploadUrlUnavailableException(e);
        }
    }

    public boolean exists(String key) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(properties.bucket()).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new UploadUrlUnavailableException(e);
        } catch (Exception e) {
            throw new UploadUrlUnavailableException(e);
        }
    }

    /** Null when signing fails: the story renders without media rather than failing the whole tray. */
    public String signedGet(String key) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucket())
                    .object(key)
                    .expiry((int) READ_EXPIRY.toSeconds())
                    .build());
        } catch (Exception e) {
            log.warn("Could not sign story media {}", key, e);
            return null;
        }
    }
}
