package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Answers "who wrote this comment", for a warning an admin filed against it. The moderation event
 * names the comment the way admin-service stores it — {@code videoId:commentId}, since comments
 * are partitioned by video and the comment id alone reaches nothing — and only interaction-service
 * can turn that into a person.
 *
 * <p>Unlike {@link VideoOwnerClient}, an unreachable interaction-service is thrown rather than
 * swallowed. That client sits on every like and comment, where dropping one notice beats stalling
 * the partition; a warning is rare, and this notice is the only way the decision reaches the
 * person it is about, so it is worth waiting for. When interaction-service gives no answer at all
 * (refused, unreachable, timed out) kafka-lib treats it as an outage and retries until it does; a
 * 5xx gets three retries and then the dead-letter topic.
 *
 * <p>Only interaction-service's own "not found" answers null: a 404 carrying its {@code
 * COMMENT_NOT_FOUND} code, for a comment it never stored, which no retry can fix. A 404 from
 * anywhere else (an {@code INTERACTION_SERVICE_URI} that lands on another service answers {@code
 * NOT_FOUND}, as does a path that moved in a deploy), or one whose body cannot be read, says
 * nothing about the comment and is thrown like a 5xx: three retries and then the DLT, instead of
 * the warning vanishing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentAuthorClient {

    private static final String COMMENT_NOT_FOUND = "COMMENT_NOT_FOUND";

    private final RestClient interactionServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommentAuthorView(Long userId) {
    }

    /**
     * @param target the report target as admin-service spells it, {@code videoId:commentId}
     * @return the author, or null when the target is unreadable or names no stored comment
     */
    public Long authorOf(String target) {
        CommentTarget comment = CommentTarget.parse(target);
        if (comment == null) {
            log.warn("Unreadable comment target '{}', dropping the notification", target);
            return null;
        }

        try {
            ApiResponse<CommentAuthorView> response = interactionServiceRestClient.get()
                    .uri("/api/v1/interactions/internal/videos/{videoId}/comments/{commentId}/author",
                            comment.videoId(), comment.commentId())
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<CommentAuthorView>>() {
                    });
            return response == null || response.data() == null ? null : response.data().userId();
        } catch (HttpClientErrorException.NotFound notFound) {
            if (ApiErrors.carriesCode(notFound, COMMENT_NOT_FOUND)) {
                log.warn("interaction-service has no comment {}, dropping the notification", target);
                return null;
            }
            throw notFound;
        }
    }

    /** admin-service's {@code videoId:commentId}. A private copy: this service does not depend on that one. */
    public record CommentTarget(long videoId, long commentId) {

        /** Null for anything that is not two ids around one colon. */
        public static CommentTarget parse(String target) {
            if (target == null) {
                return null;
            }
            String[] parts = target.split(":", -1);
            if (parts.length != 2) {
                return null;
            }
            try {
                return new CommentTarget(Long.parseLong(parts[0].strip()), Long.parseLong(parts[1].strip()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
