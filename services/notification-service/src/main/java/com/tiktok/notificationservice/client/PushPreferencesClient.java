package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.notificationservice.service.PushPreferences;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * A recipient's push switches and sleep hours, asked of user-service's internal settings contract
 * (no token; the gateway denies that prefix).
 *
 * <p>Fails closed, unlike {@link BlockClient}: null whenever user-service cannot say, and the caller
 * skips the push. The inbox entry already holds the notification, and pushing something the
 * recipient switched off is the worse failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushPreferencesClient {

    private final RestClient userServiceRestClient;

    /** Boxed: a switch a user-service from before it does not send reads as on, which it then was. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Policy(Long userId, Boolean pushLikes, Boolean pushComments, Boolean pushNewFollowers,
                          LocalTime sleepHoursStart, LocalTime sleepHoursEnd, String timeZone) {
    }

    /** @return null when user-service did not answer for {@code userId} */
    public PushPreferences preferencesOf(Long userId) {
        List<Policy> policies;
        try {
            ApiResponse<List<Policy>> response = userServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/settings").queryParam("ids", userId).build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            policies = response == null ? null : response.data();
        } catch (RestClientException e) {
            log.warn("Could not read the push settings of {}; not pushing", userId, e);
            return null;
        }
        Policy policy = policies == null ? null : policies.stream()
                .filter(p -> userId.equals(p.userId()))
                .findFirst().orElse(null);
        if (policy == null) {
            log.warn("user-service answered without the push settings of {}; not pushing", userId);
            return null;
        }
        return new PushPreferences(on(policy.pushLikes()), on(policy.pushComments()), on(policy.pushNewFollowers()),
                policy.sleepHoursStart(), policy.sleepHoursEnd(), zoneOf(policy.timeZone()));
    }

    private static boolean on(Boolean value) {
        return value == null || value;
    }

    /** Unparseable reads as unknown, which mutes nothing — the same as no zone yet. */
    private static ZoneId zoneOf(String timeZone) {
        if (timeZone == null) {
            return null;
        }
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
