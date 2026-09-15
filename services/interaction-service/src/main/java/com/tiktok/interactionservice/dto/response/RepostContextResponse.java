package com.tiktok.interactionservice.dto.response;

import java.util.List;

/**
 * Answers the repost badge's question for one video: has the viewer reposted it, and who else
 * (capped, unordered — see {@code RepostByVideoRepository.findByVideoId}) has. The client
 * intersects {@code reposterIds} against the viewer's own following list — this service has no
 * read into the follow graph, so it cannot do that filtering itself.
 */
public record RepostContextResponse(
        Long videoId,
        boolean repostedByMe,
        List<Long> reposterIds
) {
}
