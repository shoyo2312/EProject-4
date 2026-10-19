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
 * person it is about, so it is worth kafka-lib's retries and, past them, the dead-letter topic. A
 * comment interaction-service never stored is a 404 no retry can fix, and answers null.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentAuthorClient {

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
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("interaction-service has no comment {}, dropping the notification", target);
            return null;
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
