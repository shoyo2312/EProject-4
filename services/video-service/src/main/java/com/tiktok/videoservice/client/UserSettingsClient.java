package com.tiktok.videoservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.videoservice.service.UserSettingsCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * A video owner's comment audience, for {@code GET /videos/{id}/policy} to carry to
 * interaction-service. Only user-service knows it, and interaction-service has no client of its
 * own to ask with — widening the policy response is what keeps that path at one round trip.
 *
 * <p>Cached, because {@code /policy} is read on every comment post and every raw comment-list
 * load while a settings change is rare. {@link UserSettingsCache} holds the bound on staleness.
 *
 * <p>No token: the internal prefix is denied at the gateway and {@code permitAll} in user-service,
 * the same arrangement {@link BlockClient} uses.
 *
 * <p><b>Fails open</b> — an unreachable user-service answers null, which the comment guard reads
 * as no restriction. That matches the guard it sits next to: {@code commentsDisabled} already
 * fails open so a user-service outage cannot stop commenting platform-wide. Note this is the
 * opposite of chat-service's DM check, which fails closed; the asymmetry is deliberate and both
 * sides say so.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSettingsClient {

    private final RestClient userServiceRestClient;
    private final UserSettingsCache userSettingsCache;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SettingsPolicyView(Long userId, String commentAudience) {
    }

    /** The enum name user-service holds, or null when nobody could say. */
    public String commentAudience(Long userId) {
        if (userId == null) {
            return null;
        }

        String cached = userSettingsCache.getCommentAudience(userId).orElse(null);
        if (cached != null) {
            return cached;
        }

        try {
            ApiResponse<List<SettingsPolicyView>> response = userServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/settings")
                            .queryParam("ids", userId)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            // The endpoint answers every id it is asked for, defaults included, so an empty list
            // means something went wrong rather than "this account has no settings".
            if (response == null || response.data() == null || response.data().isEmpty()) {
                log.warn("user-service returned no settings for user {}; leaving comments unrestricted", userId);
                return null;
            }

            String commentAudience = response.data().get(0).commentAudience();
            if (commentAudience != null) {
                userSettingsCache.putCommentAudience(userId, commentAudience);
            }
            return commentAudience;
        } catch (RestClientException e) {
            log.warn("Could not read settings for user {}; leaving comments unrestricted", userId, e);
            return null;
        }
    }
}
