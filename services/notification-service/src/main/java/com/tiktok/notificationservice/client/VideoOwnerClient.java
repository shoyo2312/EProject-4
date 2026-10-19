package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers "who should be told about this like, comment or share". The interaction events carry
 * the video and the account that acted, never the account that owns the video, and this service
 * may not read video-service's database — so the owner is asked for over HTTP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoOwnerClient {

    /**
     * A video's owner never changes, so an answer stays right for good — and every like on a video
     * with a crowd behind it asked video-service the same question again, through its /policy read
     * and the user-settings lookup behind that. Bounded and access-ordered, so the busiest videos
     * stay while the map does not grow with the platform. Only answers are kept, never a failure,
     * so an outage is retried as soon as it ends. A video deleted after its owner was cached still
     * resolves, which is harmless: the like being announced did happen.
     */
    private static final int MAX_CACHED_OWNERS = 10_000;

    private final RestClient videoServiceRestClient;

    private final Map<String, Long> owners = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_CACHED_OWNERS;
                }
            });

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VideoPolicyView(Long userId) {
    }

    /**
     * Returns null when the owner cannot be established — an unknown video, video-service down, a
     * timeout. The caller drops that one notification rather than throwing for a retry: a missed
     * "someone liked your video" costs a badge, while retrying against an unreachable
     * video-service wedges the listener and costs every other notification behind it too.
     */
    public Long ownerOf(String videoId) {
        Long cached = owners.get(videoId);
        if (cached != null) {
            return cached;
        }
        Long ownerId = fetchOwner(videoId);
        if (ownerId != null) {
            owners.put(videoId, ownerId);
        }
        return ownerId;
    }

    private Long fetchOwner(String videoId) {
        try {
            ApiResponse<VideoPolicyView> response = videoServiceRestClient.get()
                    .uri("/api/v1/videos/{videoId}/policy", videoId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<VideoPolicyView>>() {
                    });

            Long ownerId = response == null || response.data() == null ? null : response.data().userId();
            if (ownerId == null) {
                log.warn("video-service returned no owner for videoId={}, dropping the notification", videoId);
            }
            return ownerId;
        } catch (RestClientException e) {
            log.warn("Could not resolve the owner of videoId={}, dropping the notification: {}",
                    videoId, e.getMessage());
            return null;
        }
    }
}
