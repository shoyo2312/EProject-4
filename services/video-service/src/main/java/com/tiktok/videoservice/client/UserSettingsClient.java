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
 * A video owner's comment settings — their comment audience and their comment filters — for
 * {@code GET /videos/{id}/policy} to carry to interaction-service. Only user-service knows them,
 * and interaction-service has no client of its own to ask with — widening the policy response is
 * what keeps that path at one round trip.
 *
 * <p>Cached, because {@code /policy} is read on every comment post and every raw comment-list
 * load while a settings change is rare. {@link UserSettingsCache} holds the bound on staleness.
 *
 * <p>Carries the internal token like every downstream client here, against user-service's internal
 * prefix, which the gateway denies. Its own client, on a timeout well under the one {@code /policy}'s
 * callers allow — see {@code DownstreamRestClientConfig.userSettingsRestClient}.
 *
 * <p><b>Fails open</b> — an unreachable user-service answers null, which the comment guard reads
 * as no restriction and the filters as their defaults. That matches the guard it sits next to:
 * {@code commentsDisabled} already fails open so a user-service outage cannot stop commenting
 * platform-wide. Note this is the opposite of chat-service's DM check, which fails closed; the
 * asymmetry is deliberate and both sides say so.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSettingsClient {

    private final RestClient userSettingsRestClient;
    private final UserSettingsCache userSettingsCache;

    /**
     * user-service's answer for one account. The filter fields are absent from a user-service older
     * than them, and stay null here rather than turning into a default this service would be making up.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SettingsPolicyView(Long userId, String commentAudience, Boolean filterSpamOffensive,
                                      Boolean filterAllComments, List<String> commentKeywords,
                                      List<String> forYouKeywords, List<String> followingKeywords,
                                      List<String> friendsKeywords) {

        /** Null when user-service sent none of the three: one from before them, so nothing is known. */
        FeedKeywords feedKeywords() {
            if (forYouKeywords == null && followingKeywords == null && friendsKeywords == null) {
                return null;
            }
            return new FeedKeywords(orEmpty(forYouKeywords), orEmpty(followingKeywords), orEmpty(friendsKeywords));
        }

        private static List<String> orEmpty(List<String> keywords) {
            return keywords == null ? List.of() : keywords;
        }
    }

    /** The one audience that closes a video to everyone, its owner included. */
    private static final String NO_ONE = "NO_ONE";

    /** What user-service holds for this account, or null when nobody could say. */
    public OwnerCommentSettings commentSettings(Long userId) {
        if (userId == null) {
            return null;
        }

        OwnerCommentSettings cached = userSettingsCache.get(userId).orElse(null);
        return cached != null ? cached : fetch(List.of(userId)).get(userId);
    }

    /**
     * The keywords {@code userId} filters out of each of their feeds, or null when nobody could say —
     * the feed is then served unfiltered. Same read and same cache entry as {@link #commentSettings}.
     */
    public FeedKeywords feedKeywords(Long userId) {
        OwnerCommentSettings settings = commentSettings(userId);
        return settings == null ? null : settings.feedKeywords();
    }

    /**
     * The accounts among {@code userIds} that accept comments from no one — every video they own
     * reads as comments off. Cache first, then a single call for the rest, so a feed page of
     * fifty videos is at most one trip to user-service rather than fifty. Fails open like
     * {@link #commentSettings}: an account nobody could answer for is not closed. The cache is
     * read in one MGET for the whole page, not a GET per owner.
     */
    public Set<Long> closedToComments(Collection<Long> userIds) {
        Set<Long> owners = new HashSet<>(userIds);
        owners.remove(null);
        if (owners.isEmpty()) {
            return Set.of();
        }

        Map<Long, OwnerCommentSettings> settings = new HashMap<>(userSettingsCache.getAll(owners));
        settings.putAll(fetch(owners.stream().filter(owner -> !settings.containsKey(owner)).toList()));

        Set<Long> closed = new HashSet<>();
        settings.forEach((userId, owner) -> {
            if (NO_ONE.equals(owner.commentAudience())) {
                closed.add(userId);
            }
        });
        return closed;
    }

    /**
     * One call for all of {@code userIds}, each answer cached. Empty when user-service could not
     * say — every caller reads a missing id as "no restriction".
     */
    private Map<Long, OwnerCommentSettings> fetch(List<Long> userIds) {
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

            Map<Long, OwnerCommentSettings> settings = new HashMap<>();
            response.data().stream()
                    .filter(Objects::nonNull)
                    .filter(view -> view.userId() != null && view.commentAudience() != null)
                    .forEach(view -> settings.put(view.userId(), new OwnerCommentSettings(view.commentAudience(),
                            view.filterSpamOffensive(), view.filterAllComments(), view.commentKeywords(),
                            view.feedKeywords())));
            userSettingsCache.putAll(settings);
            return settings;
        } catch (RestClientException e) {
            log.warn("Could not read settings for users {}; leaving comments unrestricted", userIds, e);
            return Map.of();
        }
    }
}
