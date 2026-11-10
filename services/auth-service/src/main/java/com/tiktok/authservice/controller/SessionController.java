package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.response.SessionResponse;
import com.tiktok.authservice.service.SessionRegistry;
import com.tiktok.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The devices signed in to the caller's account, and signing them out remotely.
 *
 * <p>An account holds at most one laptop/PC and one phone/tablet session; a sign-in on a second
 * device of the same type signs the first one out (ADMIN accounts excepted).
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Devices signed in to the account")
public class SessionController {

    private final SessionRegistry sessionRegistry;

    @GetMapping
    @Operation(summary = "List the devices signed in to this account",
            description = "Most recently active first. current=true marks the device making the request.")
    public ApiResponse<List<SessionResponse>> list(Authentication authentication) {
        return ApiResponse.success(sessionRegistry.list(userId(authentication), currentSession(authentication)));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Sign one device out",
            description = "Its refresh token stops working and its access token is rejected at once. "
                    + "404 SESSION_NOT_FOUND when the session is not this account's or already ended.")
    public ApiResponse<Void> signOut(Authentication authentication, @PathVariable Long sessionId) {
        sessionRegistry.signOut(userId(authentication), sessionId);
        return ApiResponse.success(null);
    }

    @DeleteMapping
    @Operation(summary = "Sign out every device except this one",
            description = "Returns how many devices were signed out.")
    public ApiResponse<Integer> signOutOthers(Authentication authentication) {
        return ApiResponse.success(sessionRegistry.signOutOthers(userId(authentication), currentSession(authentication)));
    }

    private static Long userId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }

    /** Null for a token minted before sessions existed — then no device is marked current. */
    private static Long currentSession(Authentication authentication) {
        return authentication.getDetails() instanceof String sid ? Long.valueOf(sid) : null;
    }
}
