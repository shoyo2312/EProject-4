package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.entity.UserBan;
import com.tiktok.interactionservice.repository.UserBanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Which comment authors are banned right now — see {@link UserBan}. The listings drop their comments;
 * the counters keep them, so a ban that lapses or is lifted brings the comments back with nothing to
 * recount.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBanRegistry {

    private final UserBanRepository userBanRepository;

    /**
     * Records a decision unless a newer one is already there — the update matches only a row no newer
     * than this decision; with no row at all, the insert does. Tried twice, because an account's first
     * two decisions can race with no row yet and the loser is refused either way.
     *
     * <p>No claim on the eventId: it is one assignment, so a redelivery only repeats it, and the topic
     * is keyed by user, so a ban and its unban arrive in order. The guard is for a decision delivered
     * late — a replay off the DLT.
     *
     * @param bannedUntil {@link UserBan#PERMANENT} for a ban with no end, null for an unban
     */
    public void record(Long userId, Instant bannedUntil, Instant decidedAt) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (userBanRepository.updateIfNotNewer(userId, bannedUntil, decidedAt)
                    || userBanRepository.insertIfAbsent(userId, bannedUntil, decidedAt)) {
                return;
            }
            if (userBanRepository.findById(userId).map(UserBan::getChangedAt)
                    .filter(changedAt -> changedAt.isAfter(decidedAt)).isPresent()) {
                break;
            }
        }
        log.info("Ban decision for user {} from {} is older than the one recorded; ignoring it", userId, decidedAt);
    }

    /** The authors among {@code userIds} banned as of now, in one read. */
    public Set<Long> bannedAmong(Collection<Long> userIds) {
        Set<Long> distinct = new HashSet<>(userIds);
        distinct.remove(null);
        if (distinct.isEmpty()) {
            return Set.of();
        }
        Instant now = Instant.now();
        return StreamSupport.stream(userBanRepository.findAllById(distinct).spliterator(), false)
                .filter(ban -> ban.isInForce(now))
                .map(UserBan::getUserId)
                .collect(Collectors.toSet());
    }
}
