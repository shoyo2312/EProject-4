package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Answers "who should be told about this like, comment or share". The interaction events carry
 * the video and the account that acted, never the account that owns the video, and this service
 * may not read video-service's database — so the owner is asked for over HTTP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoOwnerClient {

    private final RestClient videoServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VideoPolicyView(Long userId) {
    }

    /**
     * Returns null when the owner cannot be established — an unknown video, video-service down, a
     * timeout. The caller drops that one notification rather than throwing for a retry: a missed
     * "someone liked your video" costs a badge, while retrying against an unreachable
     * video-service wedges the listener and costs every other notification behind it too.
     */
    public Long ownerOf(Long videoId) {
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
