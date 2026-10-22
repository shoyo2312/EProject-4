package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.StoryAudienceScope;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.StoryAudienceResponse;
import com.tiktok.userservice.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The follow graph as service-to-service questions, for callers with no token to forward.
 *
 * <p>Separate from {@link FollowController} only because that class is mapped under
 * {@code /api/v1/users/{userId}}, which cannot also produce a literal {@code /internal/} path.
 * Being two mappings on the same URL shape is the point to watch: a request for
 * {@code /api/v1/users/internal/friendship} matches this one rather than
 * {@code /{userId}/friendship} because a literal segment outranks a path variable — and it has to,
 * since the other one requires a JWT and would answer 401 to the services that need this. The
 * same holds for {@code /internal/following} against {@code /{userId}/following}.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Follows", description = "The follow graph and what a viewer may see of it")
public class InternalFollowController {

    private final FollowService followService;

    /**
     * Why this exists at all: {@code FRIENDS} on either settings audience needs a mutual-follow
     * check, and chat-service opens conversations from WebSocket frames that carry no
     * Authorization header. video-service's {@code FriendshipClient} copes by forwarding the
     * viewer's own token off the request context; chat has none to forward. interaction-service
     * asks it for a private account's Reposts tab, where the caller may be anonymous.
     */
    @Operation(summary = "Internal: the relationship between a viewer and an account",
            description = "Edge-only, like the authenticated version: an id with no account is "
                    + "all false, and an account is never its own friend. Without userA the viewer "
                    + "is anonymous, and only privateAccount can be true.")
    @GetMapping("/api/v1/users/internal/friendship")
    public ApiResponse<FriendshipResponse> friendship(@RequestParam(required = false) Long userA,
                                                      @RequestParam Long userB) {
        return ApiResponse.success(followService.friendship(userA, userB));
    }

    /**
     * video-service asks this once per request for every private owner on the page, rather than
     * once per owner.
     */
    @Operation(summary = "Internal: which of these accounts a user follows",
            description = "Answers the subset of ids that followerId follows. More than 100 ids is "
                    + "400 TOO_MANY_PROFILE_IDS.")
    @GetMapping("/api/v1/users/internal/following")
    public ApiResponse<List<Long>> followingAmong(@RequestParam Long followerId,
                                                  @RequestParam List<Long> ids) {
        return ApiResponse.success(followService.followingAmong(followerId, ids));
    }

    /**
     * notification-service pages through this when a story goes live: FOLLOWERS for a PUBLIC
     * story, FRIENDS for a FRIENDS one. An account nobody follows is an empty page, not a 404.
     */
    @Operation(summary = "Internal: who a new story is announced to",
            description = "A page of userId's followers (FOLLOWERS) or of the followers userId follows "
                    + "back (FRIENDS), ascending by id, leaving out blocks in either direction, closed "
                    + "accounts and followers who muted userId. next is the last id when the page is "
                    + "full - pass it as after for the following page - else null. size defaults to, "
                    + "and is capped at, 500.")
    @GetMapping("/api/v1/users/internal/story-audience")
    public ApiResponse<StoryAudienceResponse> storyAudience(@RequestParam Long userId,
                                                            @RequestParam StoryAudienceScope scope,
                                                            @RequestParam(required = false) Long after,
                                                            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(followService.storyAudience(userId, scope, after, size));
    }
}
