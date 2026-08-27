package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.UserVideoStatsResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.AlreadyPublishedException;
import com.tiktok.videoservice.exception.ForeignUploadException;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.UnsupportedUploadTypeException;
import com.tiktok.videoservice.exception.UploadUrlUnavailableException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.UserVideoStats;
import com.tiktok.videoservice.repository.VideoRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
     * Hashtags as they are written in a caption. Letters and digits are matched by Unicode class
     * rather than {@code \\w}, because captions are routinely Vietnamese and "#viral" is no more
     * a tag than "#chảnh" is.
     */
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]+)");

    /**
     * Same cap as {@code @Size(max = 10)} on the request. Repeated here because that annotation
     * only guards the tags a client sends, and tags now also arrive from the description, which is
     * validated for length but says nothing about how many hashtags fit inside it.
     */
    private static final int MAX_TAGS = 10;

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
    private final VideoCache videoCache;

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
        String extension = UPLOAD_EXTENSIONS.get(mediaType(request.contentType()));
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
                .tags(normalizeTags(request.tags(), request.description()))
                .status(VideoStatus.PROCESSING)
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();

        try {
            return videoMapper.toResponse(videoRepository.save(video));
        } catch (DuplicateKeyException e) {
            // raw_file_idx. The only unique index on this collection besides _id, and _id is a
            // fresh Snowflake generated a few lines above.
            throw new AlreadyPublishedException();
        }
    }

    /**
     * A media type may carry parameters — {@code video/mp4; charset=utf-8} is the same type as
     * {@code video/mp4}, and rejecting it with a 400 tells the client its file is unsupported
     * when the type is one we accept.
     */
    private String mediaType(String contentType) {
        int parameters = contentType.indexOf(';');
        String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return type.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Tags are normalised here rather than trusted as typed, because everything downstream
     * compares them as strings: a candidate generator matching a viewer's affinity against a
     * video's tags treats "#Dance", "dance" and "Dance " as three unrelated interests, which
     * splits the signal exactly where it is thinnest. Lowercased, trimmed, {@code #} stripped,
     * blanks dropped, duplicates collapsed — and order kept, since it is the uploader's own
     * ranking of what the video is about.
     *
     * <p>The description is read as a second source because that is where uploaders actually put
     * hashtags: a caption reading "video demo của tôi #capcut #viral" was storing an empty tag
     * list, which leaves recommendation-service with no content feature at all and makes the
     * video unfindable by tag. The explicit list comes first so a client that does send tags
     * keeps its own ordering, and the two are merged rather than either winning, since a caption
     * hashtag and a typed tag mean the same thing.
     */
    private List<String> normalizeTags(List<String> tags, String description) {
        Stream<String> typed = tags == null ? Stream.of() : tags.stream();
        Stream<String> captioned = description == null
                ? Stream.of()
                : HASHTAG.matcher(description).results().map(match -> match.group(1));

        return Stream.concat(typed, captioned)
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT))
                .map(tag -> tag.startsWith("#") ? tag.substring(1).strip() : tag)
                .filter(tag -> !tag.isEmpty())
                .distinct()
                .limit(MAX_TAGS)
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
        // normalize() before reading segments, because java.net.URI does not resolve dot
        // segments on its own and every HTTP client downstream does. Without it,
        // ".../raw/{attacker}/../{victim}/file.mp4" satisfies the check below on the segment
        // after the first "raw" and then addresses the victim's object the moment anything
        // fetches it.
        String path = URI.create(rawFileUrl).normalize().getPath();
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

    /**
     * Cache-aside. The visibility check runs on whatever came back, cached or not, and never on
     * the way in: one cached entry serves every viewer, and a video the requester may not see is
     * a 404 built from that shared entry rather than a reason to keep it out of the cache.
     */
    @Override
    public VideoResponse getById(Long requesterId, String videoId) {
        VideoResponse cached = videoCache.get(videoId).orElse(null);
        if (cached != null) {
            return requireVisible(requesterId, cached, videoId);
        }

        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        VideoResponse response = videoMapper.toResponse(video);
        videoCache.put(response);

        return requireVisible(requesterId, response, videoId);
    }

    /**
     * Ordered by the request, not by the database. The caller's order is a ranking — this is what
     * recommendation-service's feed hands back — and returning documents in Mongo's order would
     * throw it away silently, leaving a feed that looks ranked and is not.
     *
     * <p>Capped at the same page maximum as every other listing: the id list arrives in a query
     * string, and nothing else stops a caller asking for ten thousand documents in one hop.
     */
    @Override
    public List<VideoResponse> getByIds(Long requesterId, List<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return List.of();
        }

        // Distinct first, so a list padded with one repeated id cannot use up the cap.
        List<String> wanted = videoIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(maxBatchSize())
                .toList();
        if (wanted.isEmpty()) {
            return List.of();
        }

        // Cache first, then Mongo for the remainder only. A ranking handed to a hundred viewers
        // in the same minute is the same ids every time, so on a warm cache the miss list is
        // usually empty and the query below never runs at all.
        Map<String, VideoResponse> found = new HashMap<>(videoCache.getAll(wanted));

        List<String> missing = wanted.stream()
                .filter(id -> !found.containsKey(id))
                .toList();

        if (!missing.isEmpty()) {
            List<VideoResponse> loaded = videoRepository.findByIdInAndDeletedAtIsNull(missing).stream()
                    .map(videoMapper::toResponse)
                    .toList();

            videoCache.putAll(loaded);
            loaded.forEach(video -> found.put(video.id(), video));
        }

        // Filtered after the lookup, not during it, so the cache holds one entry per video
        // rather than one per (video, viewer) — see getById.
        return wanted.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .filter(video -> isVisibleTo(requesterId, video))
                .toList();
    }

    private int maxBatchSize() {
        return pageableProperties.getPageable().getMaxPageSize();
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

    /**
     * The profile header's counters. Same owner/stranger split as {@link #listByUser}, so the
     * total sits over the grid it was summed from.
     */
    @Override
    public UserVideoStatsResponse getUserStats(Long requesterId, Long userId) {
        UserVideoStats stats = videoRepository.sumUserVideoStats(userId, isSelf(requesterId, userId));
        return new UserVideoStatsResponse(
                userId, stats.videoCount(), stats.totalLikes(), stats.totalViews());
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
        videoCache.evict(videoId);
    }

    private VideoResponse requireVisible(Long requesterId, VideoResponse video, String videoId) {
        if (!isVisibleTo(requesterId, video)) {
            throw new VideoNotFoundException(videoId);
        }
        return video;
    }

    /**
     * Decided from the response rather than the entity, because by this point the value may have
     * come from Redis and there is no entity. The three fields it reads — userId, status,
     * visibility — are on both, and a video the requester does not own is only theirs to see once
     * it is both PUBLISHED and PUBLIC.
     */
    private boolean isVisibleTo(Long requesterId, VideoResponse video) {
        return isSelf(requesterId, video.userId())
                || (video.visibility() == VideoVisibility.PUBLIC && video.status() == VideoStatus.PUBLISHED);
    }

    private boolean isOwner(Long requesterId, Video video) {
        return isSelf(requesterId, video.getUserId());
    }

    private boolean isSelf(Long requesterId, Long userId) {
        return requesterId != null && requesterId.equals(userId);
    }
}
