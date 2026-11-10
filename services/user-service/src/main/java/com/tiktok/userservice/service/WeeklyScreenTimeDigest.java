package com.tiktok.userservice.service;

import com.tiktok.userservice.entity.UserSettings;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.repository.ScreenTimeDayRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/**
 * Settings → Screen time → Weekly screen time updates: one inbox entry per week with last week's
 * total, sent on Monday from 09:00 in the account's zone. Runs hourly, so every zone is reached
 * within the hour after its 09:00.
 *
 * <p>Each account is claimed with a conditional update of {@code last_digest_week} in the same
 * transaction as the outbox row, so instances running at the same minute cannot both send a week,
 * and a crash between the two leaves neither. A week with no time recorded is claimed and not sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyScreenTimeDigest {

    static final LocalTime SEND_FROM = LocalTime.of(9, 0);

    private static final int PAGE_SIZE = 500;

    private final UserSettingsRepository userSettingsRepository;
    private final ScreenTimeDayRepository screenTimeDayRepository;
    private final UserEventPublisher userEventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "${user.screen-time.digest-cron}")
    public void run() {
        int queued = runOnce(Instant.now());
        if (queued > 0) {
            log.info("Queued {} weekly screen time update(s)", queued);
        }
    }

    /** @return how many updates were queued */
    public int runOnce(Instant now) {
        // No zone is more than a day ahead of UTC, so no account can be due for a later week.
        LocalDate latestWeek = LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(1);
        int queued = 0;
        Long afterId = 0L;
        while (true) {
            List<UserSettings> page = userSettingsRepository.findDigestCandidates(latestWeek, afterId,
                    PageRequest.of(0, PAGE_SIZE));
            for (UserSettings candidate : page) {
                if (sendIfDue(candidate, now)) {
                    queued++;
                }
            }
            if (page.size() < PAGE_SIZE) {
                return queued;
            }
            afterId = page.getLast().getId();
        }
    }

    private boolean sendIfDue(UserSettings candidate, Instant now) {
        ZoneId zone;
        try {
            zone = ZoneId.of(candidate.getTimeZone());
        } catch (DateTimeException ex) {
            // Only the heartbeat writes the zone, and it validates first; nothing to do but wait.
            return false;
        }
        LocalDate week = dueWeek(now, zone);
        if (week == null || (candidate.getLastDigestWeek() != null && !candidate.getLastDigestWeek().isBefore(week))) {
            return false;
        }
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            if (userSettingsRepository.claimDigestWeek(candidate.getId(), week) == 0) {
                return false;
            }
            long total = screenTimeDayRepository.sumSeconds(candidate.getUserId(), week, week.plusDays(6));
            if (total == 0) {
                return false;
            }
            userEventPublisher.publishWeeklyScreenTimeSummary(candidate.getUserId(), week, total);
            return true;
        }));
    }

    /**
     * The Monday of "last week" as seen in {@code zone} at {@code now}, or null on a Monday before
     * 09:00 local, when the week that just ended is not due yet.
     */
    static LocalDate dueWeek(Instant now, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate thisMonday = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (local.toLocalDate().equals(thisMonday) && local.toLocalTime().isBefore(SEND_FROM)) {
            return null;
        }
        return thisMonday.minusWeeks(1);
    }
}
