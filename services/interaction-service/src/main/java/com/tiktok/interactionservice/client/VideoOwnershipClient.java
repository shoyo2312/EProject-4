package com.tiktok.interactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Answers "does this account own that video", for the one caller who needs to know something
 * outside interaction-service's own data: a video's owner may delete any comment on it, not only
 * their own, and video-service is the only place that knows who a video belongs to.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoOwnershipClient {

    private final RestClient videoServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VideoOwnerView(Long userId) {
    }

    /**
     * False for anything that is not a confirmed match — a missing video, video-service being
     * unreachable, a genuine mismatch — so a lookup failure denies the extra permission rather
     * than granting it. The comment's own owner still gets to delete it either way; this only
     * ever adds permission, never removes it.
     */
    public boolean isOwnedBy(Long videoId, Long userId) {
        try {
            ApiResponse<VideoOwnerView> response = videoServiceRestClient.get()
                    .uri("/api/v1/videos/{videoId}", videoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response != null
                    && response.data() != null
                    && userId.equals(response.data().userId());
        } catch (RestClientException e) {
            log.warn("Could not confirm ownership of video {} against video-service; denying the extra permission",
                    videoId, e);
            return false;
        }
    }
}
