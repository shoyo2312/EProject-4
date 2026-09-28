package com.tiktok.videoservice.service;

import com.tiktok.common.storage.ObjectKeys;
import com.tiktok.videoservice.client.BlockClient;
import com.tiktok.videoservice.client.FollowClient;
import com.tiktok.videoservice.client.FriendshipClient;
import com.tiktok.videoservice.client.UserSettingsClient;
import com.tiktok.videoservice.config.MinioProperties;
import com.tiktok.videoservice.config.UploadLimitProperties;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.request.UploadUrlRequest;
import com.tiktok.videoservice.dto.response.CursorPage;
import com.tiktok.videoservice.dto.response.UploadUrlResponse;
import com.tiktok.videoservice.dto.response.UserVideoStatsResponse;
import com.tiktok.videoservice.dto.response.VideoPolicyResponse;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.AlreadyPublishedException;
import com.tiktok.videoservice.exception.ForeignUploadException;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.PrivateAccountException;
import com.tiktok.videoservice.exception.TooManyFollowedUsersException;
import com.tiktok.videoservice.exception.UnsupportedUploadTypeException;
import com.tiktok.videoservice.exception.UploadUrlUnavailableException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.mapper.VideoMapper;
import com.tiktok.videoservice.repository.OwnerPrivacyRepository;
import com.tiktok.videoservice.repository.UserVideoStats;
import com.tiktok.videoservice.repository.VideoRepository;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebProperties;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /** The last segment of an upload key as createUploadUrl issues it: a Snowflake and an extension. */
    private static final Pattern UPLOAD_FILE_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+");

    /**
     * How many authors one Following feed request may name. Well above what a real account follows
     * on this deployment, and low enough that the {@code $in} behind it stays a bounded set of
     * index ranges rather than a scan the client sizes. See {@link TooManyFollowedUsersException}
     * for why going over is an error instead of a truncation.
     */
    private static final int MAX_FOLLOWED_USERS = 500;

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
    private final FriendshipClient friendshipClient;
    private final UploadLimitProperties uploadLimits;
    private final QuarantinedMediaUrls quarantinedMediaUrls;
    private final BlockClient blockClient;
    private final UserSettingsClient userSettingsClient;
    private final FollowClient followClient;
    private final OwnerPrivacyRepository ownerPrivacyRepository;

    /**
     * The key is namespaced by uploader and carries a fresh Snowflake, so two clients uploading at
     * once cannot land on the same object, and a key seen in storage says who put it there.
     *
     * <p>The returned {@code fileUrl} is an {@code s3://} URI rather than any signed URL: the
     * policy signature expires in minutes, and it is the raw location that gets stored on the
     * Video and read by media-worker, possibly hours later.
     *
     * <p>The POST policy pins the key and the content type and signs a
     * {@code content-length-range} of {@code [1, maxBytes]}, so MinIO refuses an oversize or
     * empty upload at the storage edge before any bytes land. media-worker still re-checks size
     * and duration once it has the file — it is the only place duration can be checked at all.
     */
    @Override
    public UploadUrlResponse createUploadUrl(Long userId, UploadUrlRequest request) {
        String contentType = mediaType(request.contentType());
        String extension = UPLOAD_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new UnsupportedUploadTypeException(request.contentType(), UPLOAD_EXTENSIONS.keySet());
        }

        String objectKey = "%s/%d/%s.%s".formatted(UPLOAD_PREFIX, userId, Video.newId(), extension);
        long expirySeconds = minioProperties.urlExpiry().toSeconds();

        PostPolicy policy = new PostPolicy(minioProperties.bucket(),
                ZonedDateTime.now().plusSeconds(expirySeconds));
        policy.addEqualsCondition("key", objectKey);
        policy.addEqualsCondition("Content-Type", contentType);
        policy.addContentLengthRangeCondition(1L, uploadLimits.maxBytes());

        Map<String, String> formFields;
        try {
            formFields = new HashMap<>(minioClient.getPresignedPostFormData(policy));
        } catch (Exception e) {
            // minio-java declares half a dozen checked exceptions here and they all mean the same
            // thing to a caller: no URL. Logged with the bucket because the realistic cause is
            // configuration, and the message the client gets deliberately omits it.
            log.error("Failed to presign an upload POST for bucket {}", minioProperties.bucket(), e);
            throw new UploadUrlUnavailableException(e);
        }
        formFields.put("key", objectKey);
        formFields.put("Content-Type", contentType);

        String uploadUrl = "%s/%s".formatted(minioProperties.endpoint(), minioProperties.bucket());
        return new UploadUrlResponse(uploadUrl, formFields, storedUrl(objectKey), expirySeconds);
    }

    @Override
    public VideoResponse publish(Long userId, CreateVideoRequest request) {
        String rawFileUrl = storedUrl(requireOwnUpload(userId, request.rawFileUrl()));

        Video video = Video.builder()
                .id(Video.newId())
                .userId(userId)
                .title(request.title())
                .description(request.description())
                .rawFileUrl(rawFileUrl)
                .visibility(request.visibility())
                .ownerPrivate(isPrivateOwner(userId))
                .tags(normalizeTags(request.tags(), request.description()))
                .status(VideoStatus.PROCESSING)
                .viewCount(0)
                .likeCount(0)
                .commentCount(0)
                .build();

        try {
            videoRepository.save(video);
        } catch (DuplicateKeyException e) {
            // raw_file_idx. The only unique index on this collection besides _id, and _id is a
            // fresh Snowflake generated a few lines above. rawFileUrl is rebuilt from the key, so
            // two spellings of one object (s3:// and the CDN URL, say) collide here too.
            throw new AlreadyPublishedException();
        }

        // Read again after the insert. UserPrivacyEventConsumer records a switch and then stamps
        // every video the owner has: if its stamping ran before this insert, its recording did too
        // and this read sees it; if after, it rewrote this video with the rest. With the first
        // read alone, a switch landing between it and the insert would leave this video wrong for
        // good. And again after each corrective write, as the consumer does after its stamp:
        // whoever writes last checks last, so a correction that went stale on its way in is not
        // the final word.
        boolean ownerPrivate = isPrivateOwner(userId);
        while (ownerPrivate != video.isOwnerPrivate()) {
            video.changeOwnerPrivacy(ownerPrivate);
            videoRepository.updateOwnerPrivacy(video);
            ownerPrivate = isPrivateOwner(userId);
        }
        return withAccountComments(videoMapper.toResponse(video));
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
     * createUploadUrl above issues {@code raw/{userId}/{id}.{ext}} and nothing else can write
     * there. Without this check, publishing someone else's raw key republishes their video under
     * the caller's name, and the transcode pipeline does the rest.
     *
     * <p>The key is read with {@link ObjectKeys#objectKey}, the same parser media-worker fetches
     * the file with. Reading it any other way checks one object and transcodes another: this used
     * to match the segment after the first {@code raw}, so
     * {@code https://cdn/raw/1/video-media/quarantine/hls/555/source.mp4} passed as user 1's upload
     * while media-worker, reading from the bucket name on, pulled a taken-down video out of
     * quarantine. The whole key has to have the exact shape createUploadUrl issues — a prefix
     * alone would still admit {@code raw/1/x/../../2/y.mp4}, whose dot segments only an
     * encoded slash hides from normalization.
     *
     * @return the object key, for the caller to store in canonical form
     */
    private String requireOwnUpload(Long userId, String rawFileUrl) {
        String key = ObjectKeys.objectKey(rawFileUrl, minioProperties.bucket())
                .orElseThrow(ForeignUploadException::new);

        String ownPrefix = "%s/%d/".formatted(UPLOAD_PREFIX, userId);
        if (!key.startsWith(ownPrefix)
                || !UPLOAD_FILE_NAME.matcher(key.substring(ownPrefix.length())).matches()) {
            throw new ForeignUploadException();
        }
        return key;
    }

    /**
     * The form a raw upload is stored in: the key under an {@code s3://} bucket authority, which is
     * what media-worker reads and what raw_file_idx compares.
     */
    private String storedUrl(String objectKey) {
        return "s3://%s/%s".formatted(minioProperties.bucket(), objectKey);
    }

    /**
     * Cache-aside. The visibility check runs on whatever came back, cached or not, and never on
     * the way in: one cached entry serves every viewer, and a video the requester may not see is
     * a 404 built from that shared entry rather than a reason to keep it out of the cache.
     */
    @Override
    public VideoResponse getById(Long requesterId, String videoId) {
        VideoResponse video = requireVisible(requesterId, load(videoId), videoId);
        // After the cache, never into it: a signed URL expires, and the entry serves everyone.
        return withAccountComments(
                isSelf(requesterId, video.userId()) ? quarantinedMediaUrls.forOwner(video) : video);
    }

    /**
     * Owner and comment settings, and nothing the caller has to be allowed to see — so no
     * visibility check runs here. {@link VideoPolicyResponse} has the reason.
     *
     * <p>The account-level comment audience is fetched rather than read off the video, and
     * deliberately not denormalised onto the document: this endpoint is already a per-request
     * lookup, so a cached call is O(1) and near-fresh, while denormalising would mean rewriting
     * every document a creator owns each time they change the setting. The feed filter, which
     * cannot call per row, is the case that will need the opposite answer.
     */
    @Override
    public VideoPolicyResponse getPolicy(String videoId) {
        VideoResponse video = load(videoId);
        return new VideoPolicyResponse(video.id(), video.userId(), video.commentsDisabled(),
                video.durationSeconds(), userSettingsClient.commentAudience(video.userId()));
    }

    /**
     * The cached-or-Mongo read shared by {@link #getById} and {@link #getPolicy}. Deliberately
     * without a visibility check: one cached entry serves every caller, and who may see it is
     * decided by whoever asked, after the read.
     */
    private VideoResponse load(String videoId) {
        VideoResponse cached = videoCache.get(videoId).orElse(null);
        if (cached != null) {
            return cached;
        }

        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        VideoResponse response = videoMapper.toResponse(video);
        videoCache.put(response);
        return response;
    }

    /**
     * Marks the videos whose owner accepts comments from no one. On the way out, never into the
     * cache: a cached entry is the video's own state and serves every caller, while the account
     * setting changes on its own clock — {@link UserSettingsCache} already bounds how stale that
     * may be, and baking it into the video entry would stack the video cache's TTL on top.
     */
    private List<VideoResponse> withAccountComments(List<VideoResponse> videos) {
        if (videos.isEmpty()) {
            return videos;
        }
        Set<Long> closed = userSettingsClient.closedToComments(
                videos.stream().map(VideoResponse::userId).toList());
        return closed.isEmpty() ? videos : videos.stream()
                .map(video -> closed.contains(video.userId()) ? video.withAccountCommentsOff() : video)
                .toList();
    }

    private VideoResponse withAccountComments(VideoResponse video) {
        return withAccountComments(List.of(video)).get(0);
    }

    private Page<VideoResponse> withAccountComments(Page<VideoResponse> page) {
        return new PageImpl<>(withAccountComments(page.getContent()), page.getPageable(),
                page.getTotalElements());
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
        List<VideoResponse> ordered = wanted.stream()
                .map(found::get)
                .filter(Objects::nonNull)
                .toList();
        Set<Long> followed = followedPrivateOwners(requesterId, ordered);
        return withAccountComments(ordered.stream()
                .filter(video -> isVisibleTo(requesterId, video, followed))
                .toList());
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
        return feedPage(null, cursor, size);
    }

    @Override
    public CursorPage<VideoResponse> getFollowingFeed(
            Long requesterId, List<Long> followedUserIds, String cursor, Integer size) {
        // Following nobody is an empty tab, not the public feed. Returning early also keeps an
        // empty $in — which matches nothing but still costs a query — off the wire.
        if (followedUserIds == null || followedUserIds.isEmpty()) {
            return new CursorPage<>(List.of(), null);
        }
        if (followedUserIds.size() > MAX_FOLLOWED_USERS) {
            throw new TooManyFollowedUsersException(MAX_FOLLOWED_USERS);
        }

        // Deduplicated: the caller assembles this from a paged listing, and a repeat id widens the
        // $in for nothing.
        Set<Long> authors = new LinkedHashSet<>(followedUserIds);
        // The ids come from the client, so naming a private account is not the same as following
        // it. Dropped before the query rather than filtered out of the page, which would come back
        // short under a cursor that already went past what it hid.
        authors.removeAll(privateOwnersHiddenFrom(requesterId, authors));
        if (authors.isEmpty()) {
            return new CursorPage<>(List.of(), null);
        }
        return feedPage(authors, cursor, size);
    }

    /**
     * @param userIds the Following feed's authors, or null for the public feed — every author
     *                whose account is not private
     */
    private CursorPage<VideoResponse> feedPage(
            Collection<Long> userIds, String cursor, Integer size) {
        FeedCursor after = FeedCursor.decode(cursor);
        int limit = clampSize(size);

        // One row past the page. Its presence is what says there is more to come — the same answer
        // a count query gives, for the cost of one extra document instead of a second scan.
        List<Video> rows = videoRepository.findFeedPage(
                userIds,
                after == null ? null : after.createdAt(),
                after == null ? null : after.id(),
                limit + 1);

        boolean hasMore = rows.size() > limit;
        List<Video> page = hasMore ? rows.subList(0, limit) : rows;

        // Null only once the feed is exhausted. A full page that happens to end on the very last
        // video still hands back a cursor, and the next request answers empty and settles it —
        // guessing otherwise would need the count this method exists to avoid.
        String nextCursor = hasMore ? FeedCursor.of(page.get(page.size() - 1)).encode() : null;

        return new CursorPage<>(
                withAccountComments(page.stream().map(videoMapper::toResponse).toList()), nextCursor);
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
     *
     * <p>A private account's grid is for its followers, and anyone else is told so with a 403
     * rather than an empty page — the client shows "This account is private", not "No videos yet".
     * Asked of owner_privacy rather than of the videos, so an account with nothing on its grid
     * answers the same way.
     */
    @Override
    public Page<VideoResponse> listByUser(Long requesterId, Long userId, Pageable pageable) {
        if (isSelf(requesterId, userId)) {
            return withAccountComments(
                    videoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable)
                            .map(videoMapper::toResponse)
                            .map(quarantinedMediaUrls::forOwner));
        }

        if (isPrivateOwner(userId)
                && !followClient.followedAmong(requesterId, List.of(userId)).contains(userId)) {
            throw new PrivateAccountException();
        }

        Page<Video> videos;
        if (friendshipClient.areFriends(userId, requesterId)) {
            // One friendship check per page load, not per video: the answer is the same for every
            // video on this owner's grid.
            videos = videoRepository.findByUserIdAndStatusAndVisibilityInAndDeletedAtIsNullOrderByCreatedAtDesc(
                    userId, VideoStatus.PUBLISHED,
                    List.of(VideoVisibility.PUBLIC, VideoVisibility.FRIENDS), pageable);
        } else {
            videos = videoRepository.findByUserIdAndStatusAndVisibilityAndDeletedAtIsNullOrderByCreatedAtDesc(
                    userId, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, pageable);
        }

        return withAccountComments(videos.map(videoMapper::toResponse));
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

    /**
     * One Mongo aggregation per id, not the $group-by-owner query this could be — the ids in a
     * realtime flush window are usually a handful of accounts, not enough to be worth a different
     * query shape from {@link #getUserStats}.
     * ponytail: $group by owner across ids in one query if a flush window ever spans hundreds of
     * accounts.
     *
     * <p>Capped at the same page maximum as {@link #getByIds}, and for the same reason: the ids
     * arrive in a query string and every one of them costs its own Mongo aggregation, so without
     * this one request is as many aggregations as a caller cares to name. Distinct first, so a
     * list padded with one repeated id cannot use up the cap.
     */
    @Override
    public List<UserVideoStatsResponse> getUserStatsBatch(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(maxBatchSize())
                .map(userId -> getUserStats(null, userId))
                .toList();
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

    /**
     * Same owner gate as {@link #delete}. The write is unconditional and the cache entry is
     * evicted rather than rewritten — a stale PUBLIC entry outliving a switch to PRIVATE is the
     * one outcome to avoid, and the next read repopulates it.
     */
    @Override
    public VideoResponse updateVisibility(Long requesterId, String videoId, VideoVisibility visibility) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video)) {
            throw new NotVideoOwnerException(videoId);
        }

        video.changeVisibility(visibility);
        videoRepository.updateVisibility(video);
        videoCache.evict(videoId);

        return withAccountComments(videoMapper.toResponse(video));
    }

    /**
     * Same owner gate and cache handling as {@link #updateVisibility}. video-service only stores
     * the flag; interaction-service reads it back off {@code GET /videos/{id}/policy} and is what
     * actually refuses a comment.
     */
    @Override
    public VideoResponse updateCommentsDisabled(Long requesterId, String videoId, boolean disabled) {
        Video video = videoRepository.findByIdAndDeletedAtIsNull(videoId)
                .orElseThrow(() -> new VideoNotFoundException(videoId));

        if (!isOwner(requesterId, video)) {
            throw new NotVideoOwnerException(videoId);
        }

        video.changeCommentsDisabled(disabled);
        videoRepository.updateCommentsDisabled(video);
        videoCache.evict(videoId);

        return withAccountComments(videoMapper.toResponse(video));
    }

    /**
     * The single-video read, and so also the gate every interaction passes: interaction-service
     * asks this with the caller's token before a like, comment, save, repost, share or view. A
     * block between the viewer and the owner hides the video from both sides here, which is what
     * keeps a blocked account from commenting on or liking the blocker's videos.
     *
     * <p>ponytail: listings and batches ({@link #isVisibleTo}) are not block-filtered — that is one
     * user-service call per video. Needs a batch block endpoint if feeds must hide them too.
     */
    private VideoResponse requireVisible(Long requesterId, VideoResponse video, String videoId) {
        if (!isVisibleTo(requesterId, video, followedPrivateOwners(requesterId, List.of(video)))
                || blockClient.isBlockedBetween(video.userId(), requesterId)) {
            throw new VideoNotFoundException(videoId);
        }
        return video;
    }

    /**
     * Decided from the response rather than the entity, because by this point the value may have
     * come from Redis and there is no entity. The fields it reads — userId, status, visibility,
     * ownerPrivate — are on both, and a video the requester does not own is only theirs to see
     * once it is PUBLISHED and its audience takes them in.
     *
     * @param followedPrivateOwners the private owners on this request the requester follows — see
     *                              {@link #followedPrivateOwners}
     */
    private boolean isVisibleTo(Long requesterId, VideoResponse video, Set<Long> followedPrivateOwners) {
        if (isSelf(requesterId, video.userId())) {
            return true;
        }
        if (video.status() != VideoStatus.PUBLISHED) {
            return false;
        }
        return switch (video.visibility()) {
            case PUBLIC -> !video.ownerPrivate() || followedPrivateOwners.contains(video.userId());
            // ponytail: one user-service call per FRIENDS video the requester does not own. PUBLIC
            // videos short-circuit above, so a normal feed/batch makes none; a list that is mostly
            // FRIENDS videos (rare) would. Add a batch friendship endpoint if that ever shows up.
            // A private owner changes nothing here: a mutual follow already implies following.
            case FRIENDS -> friendshipClient.areFriends(video.userId(), requesterId);
            case PRIVATE -> false;
        };
    }

    /**
     * The owners on this request whose PUBLIC videos the requester may see only as a follower,
     * narrowed to the ones they do follow. One call for all of them, and none when nothing on the
     * request belongs to a private owner — every page from accounts nobody made private.
     */
    private Set<Long> followedPrivateOwners(Long requesterId, List<VideoResponse> videos) {
        Set<Long> privateOwners = videos.stream()
                .filter(video -> video.ownerPrivate() && video.visibility() == VideoVisibility.PUBLIC)
                .map(VideoResponse::userId)
                .filter(owner -> !isSelf(requesterId, owner))
                .collect(Collectors.toSet());
        return privateOwners.isEmpty() ? Set.of() : followClient.followedAmong(requesterId, privateOwners);
    }

    private boolean isPrivateOwner(Long userId) {
        return ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(userId);
    }

    /**
     * The private accounts among {@code owners} whose videos {@code requesterId} may not see:
     * neither theirs nor followed. One owner_privacy query, and a follow lookup only when one of
     * them is private.
     */
    private Set<Long> privateOwnersHiddenFrom(Long requesterId, Collection<Long> owners) {
        Set<Long> privateOwners = ownerPrivacyRepository.findByUserIdInAndPrivateAccountTrue(owners).stream()
                .map(OwnerPrivacy::getUserId)
                .filter(owner -> !isSelf(requesterId, owner))
                .collect(Collectors.toSet());
        if (privateOwners.isEmpty()) {
            return privateOwners;
        }
        Set<Long> followed = followClient.followedAmong(requesterId, privateOwners);
        return privateOwners.stream()
                .filter(owner -> !followed.contains(owner))
                .collect(Collectors.toSet());
    }

    private boolean isOwner(Long requesterId, Video video) {
        return isSelf(requesterId, video.getUserId());
    }

    private boolean isSelf(Long requesterId, Long userId) {
        return requesterId != null && requesterId.equals(userId);
    }
}
