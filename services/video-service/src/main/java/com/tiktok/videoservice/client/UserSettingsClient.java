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

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A video owner's comment audience, for {@code GET /videos/{id}/policy} to carry to
 * interaction-service. Only user-service knows it, and interaction-service has no client of its
 * own to ask with — widening the policy response is what keeps that path at one round trip.
 *
 * <p>Cached, because {@code /policy} is read on every comment post and every raw comment-list
 * load while a settings change is rare. {@link UserSettingsCache} holds the bound on staleness.
 *
 * <p>No token: the internal prefix is denied at the gateway and {@code permitAll} in user-service,
 * the same arrangement {@link BlockClient} uses. Its own client, on a timeout well under the one
 * {@code /policy}'s callers allow — see {@code DownstreamRestClientConfig.userSettingsRestClient}.
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

    private final RestClient userSettingsRestClient;
    private final UserSettingsCache userSettingsCache;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SettingsPolicyView(Long userId, String commentAudience) {
    }

    /** The one audience that closes a video to everyone, its owner included. */
    private static final String NO_ONE = "NO_ONE";

    /** The enum name user-service holds, or null when nobody could say. */
    public String commentAudience(Long userId) {
        if (userId == null) {
            return null;
        }

        String cached = userSettingsCache.getCommentAudience(userId).orElse(null);
        return cached != null ? cached : fetch(List.of(userId)).get(userId);
    }

    /**
     * The accounts among {@code userIds} that accept comments from no one — every video they own
     * reads as comments off. Cache first, then a single call for the rest, so a feed page of
     * fifty videos is at most one trip to user-service rather than fifty. Fails open like
     * {@link #commentAudience}: an account nobody could answer for is not closed. The cache is
     * read in one MGET for the whole page, not a GET per owner.
     */
    public Set<Long> closedToComments(Collection<Long> userIds) {
        Set<Long> owners = new HashSet<>(userIds);
        owners.remove(null);
        if (owners.isEmpty()) {
            return Set.of();
        }

        Map<Long, String> audiences = new HashMap<>(userSettingsCache.getCommentAudiences(owners));
        audiences.putAll(fetch(owners.stream().filter(owner -> !audiences.containsKey(owner)).toList()));

        Set<Long> closed = new HashSet<>();
        audiences.forEach((userId, audience) -> {
            if (NO_ONE.equals(audience)) {
                closed.add(userId);
            }
        });
        return closed;
    }

    /**
     * One call for all of {@code userIds}, each answer cached. Empty when user-service could not
     * say — every caller reads a missing id as "no restriction".
     */
    private Map<Long, String> fetch(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        try {
            ApiResponse<List<SettingsPolicyView>> response = userSettingsRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/settings")
                            .queryParam("ids", userIds.toArray())
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });

            // The endpoint answers every id it is asked for, defaults included, so an empty list
            // means something went wrong rather than "these accounts have no settings".
            if (response == null || response.data() == null || response.data().isEmpty()) {
                log.warn("user-service returned no settings for users {}; leaving comments unrestricted", userIds);
                return Map.of();
            }

            Map<Long, String> audiences = new HashMap<>();
            response.data().stream()
                    .filter(Objects::nonNull)
                    .filter(view -> view.userId() != null && view.commentAudience() != null)
                    .forEach(view -> audiences.put(view.userId(), view.commentAudience()));
            userSettingsCache.putCommentAudiences(audiences);
            return audiences;
        } catch (RestClientException e) {
            log.warn("Could not read settings for users {}; leaving comments unrestricted", userIds, e);
            return Map.of();
        }
    }
}
