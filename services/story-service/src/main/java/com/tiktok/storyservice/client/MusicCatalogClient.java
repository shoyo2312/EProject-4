package com.tiktok.storyservice.client;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.storyservice.exception.MusicNotFoundException;
import com.tiktok.storyservice.exception.MusicUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Checks a story's song against the music catalogue, through video-service, which holds the
 * catalogue's client id and cache: a song is looked up the same way for a story as for a video.
 *
 * <p>Fails closed: a song that cannot be checked is not mixed in from whatever the client claimed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MusicCatalogClient {

    private final RestClient videoServiceRestClient;

    /**
     * @throws MusicNotFoundException    video-service says the track cannot be used
     * @throws MusicUnavailableException video-service, or the catalogue behind it, did not answer
     */
    public MusicTrack requireTrack(String trackId) {
        ApiResponse<MusicTrack> response;
        try {
            response = videoServiceRestClient.get()
                    .uri("/api/v1/videos/internal/music/{trackId}", trackId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 || e.getStatusCode().value() == 404) {
                throw new MusicNotFoundException();
            }
            log.warn("video-service refused the lookup of track {}: {}", trackId, e.getStatusCode());
            throw new MusicUnavailableException(e);
        } catch (RestClientException e) {
            log.warn("Could not look up track {} through video-service", trackId, e);
            throw new MusicUnavailableException(e);
        }
        if (response == null || response.data() == null || response.data().downloadUrl() == null) {
            throw new MusicUnavailableException(null);
        }
        return response.data();
    }
}
