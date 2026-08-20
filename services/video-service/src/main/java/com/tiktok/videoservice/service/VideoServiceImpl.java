package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.ForeignUploadException;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.UnsupportedUploadTypeException;
import com.tiktok.videoservice.exception.UploadUrlUnavailableException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.VideoRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

    /**
     * First segment of every upload key, with the uploader's id as the second. Both halves are
     * load-bearing: {@link #requireOwnUpload} reads the id back out at publish time, and the
     * bucket's lifecycle rule expires abandoned uploads by matching this prefix.
     */
    private static final String UPLOAD_PREFIX = "raw";

    /**
     * Upload types accepted, and the extension each is stored under. Deliberately short: every
     * entry is a format the transcode pipeline has to be able to read, so widening it is a
     * media-worker decision, not a client one.
     */
    private static final Map<String, String> UPLOAD_EXTENSIONS = Map.of(
            "video/mp4", "mp4",
            "video/quicktime", "mov",
            "video/webm", "webm");

    private final VideoRepository videoRepository;
    private final VideoMapper videoMapper;
    private final SpringDataWebProperties pageableProperties;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /**
     * The key is namespaced by uploader and carries a fresh Snowflake, so two clients uploading at
     * once cannot land on the same object, and a key seen in storage says who put it there.
     *
     * <p>The returned {@code fileUrl} is an {@code s3://} URI rather than the presigned URL: the
     * presigned one carries a signature that expires in minutes, and it is the raw location that
     * gets stored on the Video and read by media-worker, possibly hours later.
     *
     * <p>ponytail: the presigned PUT pins neither content type nor size — S3 query-signing covers
     * the key and expiry only, so a client can send anything to a key we handed out. media-worker
     * is what actually reads the file and is where a real pipeline rejects it. Move to a POST
     * policy (which can sign content-length-range and content-type) if uploads need refusing at
     * the storage edge.
     */
    @Override
    public UploadUrlResponse createUploadUrl(Long userId, UploadUrlRequest request) {
        String extension = UPLOAD_EXTENSIONS.get(request.contentType().toLowerCase(Locale.ROOT));
        if (extension == null) {
            throw new UnsupportedUploadTypeException(request.contentType(), UPLOAD_EXTENSIONS.keySet());
        }

        String objectKey = "%s/%d/%s.%s".formatted(UPLOAD_PREFIX, userId, Video.newId(), extension);
        long expirySeconds = minioProperties.urlExpiry().toSeconds();

        String uploadUrl;
        try {
            uploadUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(minioProperties.bucket())
                    .object(objectKey)
                    .expiry((int) expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            // minio-java declares half a dozen checked exceptions here and they all mean the same
            // thing to a caller: no URL. Logged with the bucket because the realistic cause is
            // configuration, and the message the client gets deliberately omits it.
            log.error("Failed to presign an upload URL for bucket {}", minioProperties.bucket(), e);
            throw new UploadUrlUnavailableException(e);
        }

        return new UploadUrlResponse(
                uploadUrl, "s3://%s/%s".formatted(minioProperties.bucket(), objectKey), expirySeconds);
    }

    @Override
    public VideoResponse publish(Long userId, CreateVideoRequest request) {
        requireOwnUpload(userId, request.rawFileUrl());

        Video video = Video.builder()
                .id(Video.newId())
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .rawFileUrl(request.rawFileUrl())
                .visibility(request.visibility())
                .tags(normalizeTags(request.tags()))
                .status(VideoStatus.PROCESSING)
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();

        Video saved = videoRepository.save(video);
        return videoMapper.toResponse(saved);
    }

    /**
     * Tags are normalised here rather than trusted as typed, because everything downstream
     * compares them as strings: a candidate generator matching a viewer's affinity against a
     * video's tags treats "#Dance", "dance" and "Dance " as three unrelated interests, which
     * splits the signal exactly where it is thinnest. Lowercased, trimmed, {@code #} stripped,
     * blanks dropped, duplicates collapsed — and order kept, since it is the uploader's own
     * ranking of what the video is about.
     */
    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }

        return tags.stream()
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                .map(tag -> tag.startsWith("#") ? tag.substring(1).strip() : tag)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * {@code @ValidMediaUrl} answers "does this point at our storage" — scheme, host, bucket. It
     * says nothing about <em>whose</em> file it is, and every upload key already carries that:
     * createUploadUrl above issues {@code raw/{userId}/...} and nothing else can write there.
     * Without this check, publishing someone else's raw key republishes their video under the
     * caller's name, and the transcode pipeline does the rest.
     *
     * <p>Matched on the segment after {@code raw/} rather than on a fixed prefix, because the
     * same key reaches this method as an {@code s3://bucket/key} URI and as an {@code https} CDN
     * URL, which put a different number of segments in front of it. A URL with no {@code raw}
     * segment at all is rejected too: it is not something this service ever handed out.
     */
    private void requireOwnUpload(Long userId, String rawFileUrl) {
        String path = URI.create(rawFileUrl).getPath();
        if (path == null) {
            throw new ForeignUploadException();
        }

        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (UPLOAD_PREFIX.equals(segments[i])) {
                if (!String.valueOf(userId).equals(segments[i + 1])) {
                    throw new ForeignUploadException();
                }
                return;
            }
        }

        throw new ForeignUploadException();
    }

    @Override
    public VideoResponse getById(Long requesterId, String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video) && !isPubliclyVisible(video)) {
            throw new VideoNotFoundException(videoId);
        }

        return videoMapper.toResponse(video);
    }

    /**
     * Cursor-paged rather than offset-paged; see {@code VideoRepositoryCustom.findFeedPage} for
     * what offset paging cost here. {@link #listByUser} below keeps offsets on purpose — a profile
     * grid is finite, gets jumped around in, and its owner has a use for a total.
     */
    @Override
    public CursorPage<VideoResponse> getFeed(String cursor, Integer size) {
        FeedCursor after = FeedCursor.decode(cursor);
        int limit = clampSize(size);

        // One row past the page. Its presence is what says there is more to come — the same answer
        // a count query gives, for the cost of one extra document instead of a second scan.
        List<Video> rows = videoRepository.findFeedPage(
                after == null ? null : after.createdAt(),
                after == null ? null : after.id(),
                limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Video> page = hasMore ? rows.subList(0, limit) : rows;

        // Null only once the feed is exhausted. A full page that happens to end on the very last
        // video still hands back a cursor, and the next request answers empty and settles it —
        // guessing otherwise would need the count this method exists to avoid.
        String nextCursor = hasMore ? FeedCursor.of(page.get(page.size() - 1)).encode() : null;

        return new CursorPage<>(page.stream().map(videoMapper::toResponse).toList(), nextCursor);
    }

    /**
     * The feed no longer goes through Spring Data's Pageable resolver, so the cap that
     * {@link com.tiktok.videoservice.config.PageableConfig} restores for the other endpoints does
     * not reach it. Same property applied by hand, rather than a second knob free to drift from it:
     * {@code ?size=100000} has to stay clamped whichever pagination style the endpoint uses.
     */
    private int clampSize(Integer size) {
        SpringDataWebProperties.Pageable limits = pageableProperties.getPageable();
        return size == null
                ? limits.getDefaultPageSize()
                : Math.clamp(size, 1, limits.getMaxPageSize());
    }

    /**
     * The owner sees their whole shelf; anyone else sees only what is actually published and
     * public — the same line {@link #getById} draws for a single video, applied to the list.
     * The endpoint takes no token, so requesterId is null for anonymous callers and they fall
     * to the filtered branch.
     */
    @Override
    public Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable) {
        Page<Video> videos = isSelf(requesterId, userId)
                ? videoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : videoRepository.findByUserIdAndStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtDesc(
                        userId, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, pageable);

        return videos.map(videoMapper::toResponse);
    }

    @Override
    public void delete(Long requesterId, String videoId) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video)) {
            throw new NotVideoOwnerException(videoId);
        }

        video.markDeleted();
        videoRepository.updateSoftDeleted(video);
    }

    private boolean isOwner(Long requesterId, Video video) {
        return isSelf(requesterId, video.getUserId());
    }

    private boolean isSelf(Long requesterId, Long userId) {
        return requesterId != null && requesterId.equals(userId);
    }

    private boolean isPubliclyVisible(Video video) {
        return video.getVisibility() == VideoVisibility.PUBLIC && video.getStatus() == VideoStatus.PUBLISHED;
    }
}
