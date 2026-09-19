package com.tiktok.interactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.exception.VideoNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Answers "does this account own that video", "are comments off" and "how long is it really", for
 * the callers who need something outside interaction-service's own data: a video's owner may delete
 * any comment on it, not only their own, and video-service is the only place that knows who a video
 * belongs to or what the transcode probed its duration to be.
 *
 * <p>Reads {@code /policy} rather than the video itself. {@code GET /videos/{id}} filters by
 * visibility and this client sends no token, so it answered 404 for exactly the videos where these
 * two questions matter: the owner of a PRIVATE or still-PROCESSING video could not delete comments
 * on it, and comments they had switched off stayed on, because the lookup fails open.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoOwnershipClient {

    private final RestClient videoServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VideoPolicyView(Long userId, boolean commentsDisabled, Integer durationSeconds) {
    }

    /**
     * Refuses an interaction with a video the caller may not see, or one that does not exist.
     *
     * <p>Unlike the questions below this one is about the viewer, so it is asked of
     * {@code GET /videos/{id}} with the caller's own token: that is where video-service decides
     * PRIVATE, FRIENDS and not-yet-PUBLISHED. Without it any id was accepted — a private video's
     * comment thread was readable by anyone holding the id, and a made-up id could be shared into
     * trending.
     *
     * <p>Only a 404 refuses. Anything else — video-service down, a timeout — lets the interaction
     * through, the same way the comments switch fails open: an outage there should not stop every
     * like on the platform, and video-service still refuses to serve a hidden video itself.
     */
    public void requireVisible(Long videoId) {
        try {
            videoServiceRestClient.get()
                    .uri("/api/v1/videos/{videoId}", videoId)
                    .headers(headers -> {
                        String token = callerAuthorization();
                        if (token != null) {
                            headers.set(HttpHeaders.AUTHORIZATION, token);
                        }
                    })
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new VideoNotFoundException(videoId);
        } catch (RestClientException e) {
            log.warn("Could not confirm video {} is visible, letting the interaction through", videoId, e);
        }
    }

    private static String callerAuthorization() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION)
                : null;
    }

    /**
     * False for anything that is not a confirmed match — a missing video, video-service being
     * unreachable, a genuine mismatch — so a lookup failure denies the extra permission rather
     * than granting it. The comment's own owner still gets to delete it either way; this only
     * ever adds permission, never removes it.
     */
    public boolean isOwnedBy(Long videoId, Long userId) {
        VideoPolicyView view = fetch(videoId);
        return view != null && userId.equals(view.userId());
    }

    /**
     * Whether the owner has switched comments off for this video. Fails open — a missing video or
     * an unreachable video-service answers false, so a dependency outage never blocks commenting.
     */
    public boolean areCommentsDisabled(Long videoId) {
        VideoPolicyView view = fetch(videoId);
        return view != null && view.commentsDisabled();
    }

    /**
     * The video's real length in milliseconds, as probed during transcoding, or 0 when nobody
     * knows it yet — the transcode has not finished, or video-service could not be reached. The
     * caller needs the difference: a duration this service confirmed can be trusted as the
     * denominator of a watch ratio, a missing one leaves the client's own number to be treated
     * with suspicion. Zero rather than null for "unknown" because every caller has to handle it
     * either way, and a length of zero is not a thing a video can have.
     */
    public long durationMs(Long videoId) {
        VideoPolicyView view = fetch(videoId);
        if (view == null || view.durationSeconds() == null || view.durationSeconds() <= 0) {
            return 0;
        }
        return view.durationSeconds() * 1000L;
    }

    private VideoPolicyView fetch(Long videoId) {
        try {
            ApiResponse<VideoPolicyView> response = videoServiceRestClient.get()
                    .uri("/api/v1/videos/{videoId}/policy", videoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response != null ? response.data() : null;
        } catch (RestClientException e) {
            log.warn("Could not reach video-service for video {}; treating the lookup as inconclusive",
                    videoId, e);
            return null;
        }
    }
}
