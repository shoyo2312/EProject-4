package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mutual-follow check as a service-to-service question, for callers with no token to forward.
 *
 * <p>Separate from {@link FollowController} only because that class is mapped under
 * {@code /api/v1/users/{userId}}, which cannot also produce a literal {@code /internal/} path.
 * Being two mappings on the same URL shape is the point to watch: a request for
 * {@code /api/v1/users/internal/friendship} matches this one rather than
 * {@code /{userId}/friendship} because a literal segment outranks a path variable — and it has to,
 * since the other one requires a JWT and would answer 401 to the services that need this.
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
     * viewer's own token off the request context; chat has none to forward.
     */
    @Operation(summary = "Internal: whether two accounts follow each other",
            description = "Edge-only, like the authenticated version: an id with no account is "
                    + "simply not a friend, and an account is never its own friend.")
    @GetMapping("/api/v1/users/internal/friendship")
    public ApiResponse<FriendshipResponse> friendship(@RequestParam Long userA, @RequestParam Long userB) {
        return ApiResponse.success(followService.friendship(userA, userB));
    }
}
