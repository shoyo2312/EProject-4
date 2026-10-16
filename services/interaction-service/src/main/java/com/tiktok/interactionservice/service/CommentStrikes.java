package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.config.CommentStrikeProperties;
import com.tiktok.interactionservice.exception.CommentingRestrictedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strikes against an account's commenting, and the restriction enough of them earn.
 *
 * <p>A strike is a comment the rules refused (422), or one an admin removed. {@code threshold}
 * strikes within {@code window} restrict commenting for the next duration in {@code restrictions} —
 * 1 hour, then a day, then a week — and the strike count starts over. The step resets after
 * {@code stepWindow} without a restriction, so an account that slipped once a month ago starts from
 * the shortest again.
 *
 * <p>Held comments are not strikes: a hold is a question for the video's owner, and an owner deleting
 * one is not a finding that it broke a rule. Nor is anything the classifier said: it can hold, not
 * judge.
 *
 * <p>Redis, like the rate limits, and fails open like them: Redis down means no strike and no
 * restriction, never "nobody may comment".
 */
@Slf4j
@Component
public class CommentStrikes {

    /**
     * Counts one strike and, at the threshold, starts the next restriction — in one script, so two
     * strikes landing together cannot both read "one short" and neither restrict. Returns {strikes
     * counted, the restriction started in millis or 0}.
     *
     * KEYS: strikes, restriction, step. ARGV: threshold, window ms, step window ms, then each
     * restriction duration in ms.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> STRIKE = RedisScript.of("""
            local strikes = redis.call('INCR', KEYS[1])
            if strikes == 1 or redis.call('PTTL', KEYS[1]) < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if strikes < tonumber(ARGV[1]) then
              return {strikes, 0}
            end
            local step = redis.call('INCR', KEYS[3])
            redis.call('PEXPIRE', KEYS[3], ARGV[3])
            local index = math.min(step, #ARGV - 3)
            local duration = tonumber(ARGV[3 + index])
            redis.call('SET', KEYS[2], step, 'PX', duration)
            redis.call('DEL', KEYS[1])
            return {strikes, duration}
            """, List.class);

    /** Where an account stands, for the admin console. */
    public record Standing(Duration restrictedFor, long strikes, long step) {
    }

    private final StringRedisTemplate redisTemplate;
    private final CommentStrikeProperties properties;

    public CommentStrikes(StringRedisTemplate redisTemplate, CommentStrikeProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /** @throws CommentingRestrictedException while a restriction is in force, with how long it has left */
    public void requireNotRestricted(Long userId) {
        restriction(userId).ifPresent(remaining -> {
            throw new CommentingRestrictedException(remaining);
        });
    }

    /** How long the account's restriction has left, if it has one. */
    public Optional<Duration> restriction(Long userId) {
        try {
            Long remaining = redisTemplate.getExpire(restrictedKey(userId), java.util.concurrent.TimeUnit.MILLISECONDS);
            return remaining != null && remaining > 0 ? Optional.of(Duration.ofMillis(remaining)) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Comment restriction unavailable, treating user {} as unrestricted: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Counts a strike against {@code userId}.
     *
     * @param why for the log line, never the comment's text
     * @return the restriction this strike started, if it reached the threshold
     */
    public Optional<Duration> strike(Long userId, String why) {
        if (properties.threshold() <= 0 || properties.restrictions().isEmpty()) {
            return Optional.empty();
        }
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(properties.threshold()));
        args.add(String.valueOf(properties.window().toMillis()));
        args.add(String.valueOf(properties.stepWindow().toMillis()));
        properties.restrictions().forEach(duration -> args.add(String.valueOf(duration.toMillis())));

        List<?> result;
        try {
            result = redisTemplate.execute(STRIKE,
                    List.of(strikesKey(userId), restrictedKey(userId), stepKey(userId)), args.toArray());
        } catch (RuntimeException e) {
            log.warn("Could not count a comment strike against user {} ({}): {}", userId, why, e.getMessage());
            return Optional.empty();
        }
        if (result == null || result.size() != 2) {
            return Optional.empty();
        }
        long restrictedMillis = ((Number) result.get(1)).longValue();
        if (restrictedMillis <= 0) {
            log.info("Comment strike {} against user {} ({})", result.get(0), userId, why);
            return Optional.empty();
        }
        Duration restriction = Duration.ofMillis(restrictedMillis);
        log.info("User {} may not comment for {} after {} strikes (last: {})", userId, restriction, result.get(0), why);
        return Optional.of(restriction);
    }

    public Standing standing(Long userId) {
        Duration restricted = restriction(userId).orElse(Duration.ZERO);
        try {
            List<String> values = redisTemplate.opsForValue().multiGet(List.of(strikesKey(userId), stepKey(userId)));
            return new Standing(restricted, number(values == null ? null : values.get(0)),
                    number(values == null ? null : values.get(1)));
        } catch (RuntimeException e) {
            log.warn("Comment strikes unavailable for user {}: {}", userId, e.getMessage());
            return new Standing(restricted, 0, 0);
        }
    }

    /**
     * Ends a restriction and forgets the strikes and the step — for an admin undoing what a bad term
     * did to innocent accounts.
     */
    public void lift(Long userId, Long adminId) {
        redisTemplate.delete(List.of(strikesKey(userId), restrictedKey(userId), stepKey(userId)));
        log.info("Admin {} cleared the comment restriction and strikes of user {}", adminId, userId);
    }

    private static long number(String value) {
        return value == null ? 0 : Long.parseLong(value);
    }

    private static String strikesKey(Long userId) {
        return "interaction:comment-strikes:" + userId;
    }

    private static String restrictedKey(Long userId) {
        return "interaction:comment-restricted:" + userId;
    }

    private static String stepKey(Long userId) {
        return "interaction:comment-restriction-step:" + userId;
    }
}
