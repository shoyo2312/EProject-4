package com.tiktok.authservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One QR sign-in in Redis: a hash at {@code auth:qr:{id}} that lives a couple of minutes.
 *
 * <p>Every step that changes it is one Lua script, never a read followed by a write. Two phones
 * scanning the same code, or a browser polling twice in the same instant, would otherwise both
 * read "PENDING" / "APPROVED" and both win: two accounts claiming one code, or one approval minted
 * into two sessions. The script's answer is the result of the check, the same rule as
 * {@code RefreshTokenRepository.claimForRotation}.
 *
 * <p>Fields: {@code s} status, {@code p} SHA-256 of the poll token, {@code d}/{@code l} the
 * browser's device label and location, {@code u}/{@code n} the scanning account's id and name.
 * Only the hash of the poll token is kept, so comparing it in Lua leaks nothing worth timing.
 */
@Component
@RequiredArgsConstructor
class QrLoginStore {

    static final String KEY_PREFIX = "auth:qr:";

    /** Written whole, with its expiry, so a code can never exist without one. */
    private static final RedisScript<Long> CREATE = RedisScript.of("""
            redis.call('HSET', KEYS[1], 's', 'PENDING', 'p', ARGV[1], 'd', ARGV[2], 'l', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return 1
            """, Long.class);

    /**
     * -1 gone, 0 someone else's, 1 claimed for this account. Scanning again from the same account
     * is a success, so a phone that lost the response can retry. A scan near the end of the code's
     * life stretches it to {@code ARGV[3]} ms, so the owner is not cut off mid-confirm.
     */
    private static final RedisScript<Long> SCAN = RedisScript.of("""
            local s = redis.call('HGET', KEYS[1], 's')
            if not s then return -1 end
            if s == 'PENDING' then
              redis.call('HSET', KEYS[1], 's', 'SCANNED', 'u', ARGV[1], 'n', ARGV[2])
            elseif not (s == 'SCANNED' and redis.call('HGET', KEYS[1], 'u') == ARGV[1]) then
              return 0
            end
            if redis.call('PTTL', KEYS[1]) < tonumber(ARGV[3]) then
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end
            return 1
            """, Long.class);

    /**
     * The phone's answer: ARGV[2] is the new status. -1 gone, 0 not this account's scan, 1 done.
     * Repeating the same answer is a success; changing it is not.
     */
    private static final RedisScript<Long> DECIDE = RedisScript.of("""
            local v = redis.call('HMGET', KEYS[1], 's', 'u')
            if not v[1] then return -1 end
            if v[2] ~= ARGV[1] then return 0 end
            if v[1] == 'SCANNED' then
              redis.call('HSET', KEYS[1], 's', ARGV[2])
              return 1
            end
            if v[1] == ARGV[2] then return 1 end
            return 0
            """, Long.class);

    /**
     * The browser's poll. A final answer (APPROVED / REJECTED) deletes the code in the same step,
     * so it is handed out exactly once. A wrong poll token reads as gone.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> POLL = RedisScript.of("""
            local v = redis.call('HMGET', KEYS[1], 's', 'p', 'u', 'n')
            if not v[1] or v[2] ~= ARGV[1] then return {'EXPIRED'} end
            if v[1] == 'APPROVED' or v[1] == 'REJECTED' then
              redis.call('DEL', KEYS[1])
              return {v[1], v[3] or '', v[4] or ''}
            end
            if v[1] == 'SCANNED' then return {v[1], v[3], v[4]} end
            return {v[1]}
            """, List.class);

    /** What a scan learns about the code it claimed. */
    record ScannedCode(String deviceLabel, String location, long remainingMillis) {
    }

    /** One poll's view. {@code userId}/{@code username} are null before a scan. */
    record PolledCode(String status, Long userId, String username) {
    }

    enum Claim { GONE, TAKEN, OK }

    private final StringRedisTemplate redisTemplate;

    void create(String qrId, String pollTokenHash, String deviceLabel, String location, Duration ttl) {
        redisTemplate.execute(CREATE, List.of(key(qrId)), pollTokenHash, orEmpty(deviceLabel),
                orEmpty(location), String.valueOf(ttl.toMillis()));
    }

    Claim scan(String qrId, Long userId, String username, Duration minimumLeft) {
        Long result = redisTemplate.execute(SCAN, List.of(key(qrId)), String.valueOf(userId),
                username, String.valueOf(minimumLeft.toMillis()));
        return claim(result);
    }

    /** Read after a successful {@link #scan}; the fields it needs never change after create. */
    ScannedCode describe(String qrId) {
        List<Object> fields = redisTemplate.opsForHash().multiGet(key(qrId), List.of("d", "l"));
        Long ttl = redisTemplate.getExpire(key(qrId), TimeUnit.MILLISECONDS);
        return new ScannedCode(blankToNull(fields.get(0)), blankToNull(fields.get(1)),
                ttl == null || ttl < 0 ? 0 : ttl);
    }

    Claim decide(String qrId, Long userId, String status) {
        return claim(redisTemplate.execute(DECIDE, List.of(key(qrId)), String.valueOf(userId), status));
    }

    @SuppressWarnings("unchecked")
    PolledCode poll(String qrId, String pollTokenHash) {
        List<Object> answer = redisTemplate.execute(POLL, List.of(key(qrId)), pollTokenHash);
        if (answer == null || answer.isEmpty()) {
            return new PolledCode("EXPIRED", null, null);
        }
        String status = String.valueOf(answer.get(0));
        String userId = answer.size() > 1 ? blankToNull(answer.get(1)) : null;
        String username = answer.size() > 2 ? blankToNull(answer.get(2)) : null;
        return new PolledCode(status, userId == null ? null : Long.valueOf(userId), username);
    }

    private static Claim claim(Long result) {
        if (result == null || result < 0) {
            return Claim.GONE;
        }
        return result == 0 ? Claim.TAKEN : Claim.OK;
    }

    private static String key(String qrId) {
        return KEY_PREFIX + qrId;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isEmpty() ? null : text;
    }
}
