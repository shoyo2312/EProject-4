package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a video's comments may go out on its socket topic, which anyone can subscribe to.
 *
 * <p>REST shows a video's comments only to someone who may see the video, and none at all once its
 * owner switches them off. The topic cannot do the same per viewer: the simple broker delivers to
 * whoever subscribed, and a subscription is only checked for shape (StompSubscriptionInterceptor).
 * So the check sits on the sending side instead — a frame goes out only for a video everyone may
 * see, with comments on. The owner of a PRIVATE video, and friends on a FRIENDS one, get no live
 * comments there and read them through REST, which is where the per-viewer check is.
 *
 * <p>Asked as an anonymous viewer, of the endpoint the app reads a video from, so the answer is
 * video-service's rule for everyone rather than a copy of it here.
 *
 * <p>Fails closed, unlike interaction-service's lookups: realtime is a layer over REST, so a frame
 * held back costs a stale thread until the next fetch, while a frame sent wrongly shows a private
 * video's comments to strangers.
 *
 * <p>Answers are kept for {@link #TTL}, or a busy thread would cost one call per comment on every
 * replica. A state change on the video drops its entry at once (VideoStateFanout). Switching
 * comments off has no event, so frames stop within the TTL — and interaction-service refuses new
 * comments from that moment, so only likes and deletions can still go out in the meantime.
 */
@Slf4j
@Component
public class VideoAudienceClient {

    static final Duration TTL = Duration.ofSeconds(30);

    /** Past this many entries, expired ones are swept on the next write so the map cannot only grow. */
    private static final int SWEEP_THRESHOLD = 10_000;

    private static final ParameterizedTypeReference<ApiResponse<VideoView>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient videoServiceClient;
    private final Clock clock;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    @Autowired
    public VideoAudienceClient(RestClient.Builder builder,
                               @Value("${downstream.video-service-uri}") String videoServiceUri) {
        this(builder, videoServiceUri, Clock.systemUTC());
    }

    VideoAudienceClient(RestClient.Builder builder, String videoServiceUri, Clock clock) {
        this.videoServiceClient = builder.baseUrl(videoServiceUri).build();
        this.clock = clock;
    }

    public boolean commentsArePublic(String videoId) {
        Instant now = clock.instant();
        Answer cached = answers.get(videoId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.open();
        }
        Boolean open = fetch(videoId);
        if (open == null) {
            // Not remembered: the next frame asks again, so recovery is not held back a whole TTL.
            return false;
        }
        if (answers.size() >= SWEEP_THRESHOLD) {
            answers.values().removeIf(answer -> !now.isBefore(answer.expiresAt()));
        }
        answers.put(videoId, new Answer(open, now.plus(TTL)));
        return open;
    }

    /** For a video whose status or visibility just changed: the next frame asks again. */
    public void forget(String videoId) {
        answers.remove(videoId);
    }

    /** @return null when video-service could not answer */
    private Boolean fetch(String videoId) {
        try {
            ApiResponse<VideoView> response = videoServiceClient.get()
                    .uri("/api/v1/videos/{videoId}", videoId)
                    .retrieve()
                    .body(RESPONSE_TYPE);
            VideoView video = response == null ? null : response.data();
            return video != null && video.commentsArePublic();
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            log.warn("Could not check who may see video {}; holding its comment frames back: {}",
                    videoId, e.getMessage());
            return null;
        }
    }

    /**
     * The status and visibility are checked again although an anonymous read only answers for a
     * PUBLISHED, PUBLIC video: this is the one check between a comment and every open socket.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VideoView(String status, String visibility, boolean commentsDisabled) {

        boolean commentsArePublic() {
            return "PUBLISHED".equals(status) && "PUBLIC".equals(visibility) && !commentsDisabled;
        }
    }

    private record Answer(boolean open, Instant expiresAt) {
    }
}
