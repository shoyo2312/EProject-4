package com.tiktok.userservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.userservice.dto.response.ScreenTimeDayResponse;
import com.tiktok.userservice.dto.response.ScreenTimeWeekResponse;
import com.tiktok.userservice.entity.ScreenTimeDay;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.repository.ScreenTimeDayRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class ScreenTimeServiceImpl implements ScreenTimeService {

    private final ScreenTimeDayRepository screenTimeDayRepository;
    private final UserSettingsRepository userSettingsRepository;

    @Override
    @Transactional
    public void recordHeartbeat(Long userId, int seconds, String timeZone, Instant receivedAt) {
        ZoneId zone = zoneOf(timeZone);
        int counted = Math.min(seconds, MAX_HEARTBEAT_SECONDS);

        userSettingsRepository.upsertTimeZone(SnowflakeIdGenerator.nextId(), userId, zone.getId());

        ZonedDateTime local = receivedAt.atZone(zone);
        boolean night = ScreenTimeService.isNight(local.toLocalTime());
        screenTimeDayRepository.addSeconds(SnowflakeIdGenerator.nextId(), userId, local.toLocalDate(),
                night ? 0 : counted, night ? counted : 0);
    }

    @Override
    @Transactional(readOnly = true)
    public ScreenTimeWeekResponse getWeek(Long userId, LocalDate weekStart) {
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new InvalidUserSettingsException("weekStart: must be a Monday, got " + weekStart);
        }
        Map<LocalDate, ScreenTimeDay> stored = screenTimeDayRepository
                .findByUserIdAndLocalDateBetweenAndDeletedAtIsNull(userId, weekStart, weekStart.plusDays(6))
                .stream()
                .collect(Collectors.toMap(ScreenTimeDay::getLocalDate, Function.identity()));

        List<ScreenTimeDayResponse> days = IntStream.range(0, 7)
                .mapToObj(weekStart::plusDays)
                .map(date -> {
                    ScreenTimeDay day = stored.get(date);
                    return day == null
                            ? new ScreenTimeDayResponse(date, 0, 0)
                            : new ScreenTimeDayResponse(date, day.getDaySeconds(), day.getNightSeconds());
                })
                .toList();
        return new ScreenTimeWeekResponse(weekStart, days);
    }

    private static ZoneId zoneOf(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException ex) {
            throw new InvalidUserSettingsException("timeZone: '" + timeZone + "' is not a known time zone");
        }
    }
}
