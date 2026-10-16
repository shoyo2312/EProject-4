package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.config.CommentLimitProperties;
import com.tiktok.interactionservice.exception.DuplicateCommentException;
import com.tiktok.moderation.text.TextNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Refuses the same comment posted again: in the same place within the window, or in more places
 * than a person pastes one comment into. A place is a video's thread, or one comment on it replied
 * to — answering two people with the same "đúng rồi" is a conversation, pasting it under every
 * comment is not. Copy-paste is what spam looks like once a link is held — the same "check my
 * profile" under every trending video.
 *
 * <p>The text is matched as the rules read it (case, punctuation, diacritics and spacing ignored)
 * and kept only as a hash: Redis never holds a comment.
 *
 * <p>A claim, taken after the rules have passed the comment and released if storing it fails — so a
 * refused comment sent again is refused for what it says, not called a duplicate, and a retry after
 * a server error is not refused as a copy of itself. Fails open, like every limiter here.
 */
@Slf4j
@Component
public class DuplicateCommentGuard {

    /**
     * KEYS: this text in this place, this text anywhere. ARGV: window millis, most copies.
     * Returns {0, 0} when claimed, else {1 = same place | 2 = too many copies, millis left}.
     */
    private static final RedisScript<List> CLAIM = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return {1, redis.call('PTTL', KEYS[1])}
            end
            local copies = tonumber(redis.call('GET', KEYS[2]) or '0')
            if copies >= tonumber(ARGV[2]) then
              local ttl = redis.call('PTTL', KEYS[2])
              if ttl < 0 then
                redis.call('PEXPIRE', KEYS[2], ARGV[1])
                ttl = tonumber(ARGV[1])
              end
              return {2, ttl}
            end
            redis.call('SET', KEYS[1], '1', 'PX', ARGV[1])
            local count = redis.call('INCR', KEYS[2])
            if count == 1 or redis.call('PTTL', KEYS[2]) < 0 then
              redis.call('PEXPIRE', KEYS[2], ARGV[1])
            end
            return {0, 0}
            """, List.class);

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            redis.call('DEL', KEYS[1])
            if redis.call('DECR', KEYS[2]) <= 0 then
              redis.call('DEL', KEYS[2])
            end
            return 1
            """, Long.class);

    /** What to give back if the comment never got stored. */
    public interface Claim {

        Claim NONE = () -> {
        };

        void release();
    }

    private final StringRedisTemplate redisTemplate;
    private final CommentLimitProperties.Duplicate limits;

    public DuplicateCommentGuard(StringRedisTemplate redisTemplate, CommentLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.limits = properties.duplicate();
    }

    /**
     * @param parentId the comment replied to, as the client named it; null for a top-level comment
     * @throws DuplicateCommentException when this account posted the same text in the same place
     *                                   within the window, or in too many places
     */
    public Claim claim(Long userId, Long videoId, Long parentId, String content) {
        if (limits.maxCopies() <= 0 || limits.window() == null || limits.window().isZero()) {
            return Claim.NONE;
        }
        String hash = hash(TextNormalizer.fingerprint(content));
        List<String> keys = List.of(
                "interaction:comment-dup:%d:%d:%d:%s".formatted(userId, videoId, parentId == null ? 0 : parentId, hash),
                "interaction:comment-dup:%d:%s".formatted(userId, hash));

        List<?> result;
        try {
            result = redisTemplate.execute(CLAIM, keys,
                    String.valueOf(limits.window().toMillis()), String.valueOf(limits.maxCopies()));
        } catch (RuntimeException e) {
            log.warn("Duplicate comment check unavailable, allowing the comment: {}", e.getMessage());
            return Claim.NONE;
        }
        if (result != null && result.size() == 2 && ((Number) result.get(0)).longValue() > 0) {
            throw new DuplicateCommentException(Duration.ofMillis(Math.max(0, ((Number) result.get(1)).longValue())));
        }
        return () -> release(keys);
    }

    private void release(List<String> keys) {
        try {
            redisTemplate.execute(RELEASE, keys);
        } catch (RuntimeException e) {
            log.warn("Could not release a duplicate-comment claim; the same text is refused until it "
                    + "expires: {}", e.getMessage());
        }
    }

    /** 128 bits of SHA-256: unique enough per account, half the key length. */
    private static String hash(String fingerprint) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
