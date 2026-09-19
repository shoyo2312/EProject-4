package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.request.SocialLinkRequest;
import com.tiktok.authservice.dto.request.SocialLoginRequest;
import com.tiktok.authservice.dto.response.SocialLoginResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.service.OAuthService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token exchange: the client's SDK has already signed the user in at the provider, and posts the
 * token it got here to trade it for a session of ours. Web and mobile use the same endpoints —
 * Google Identity Services and {@code google_sign_in} both yield an ID token, the Facebook JS SDK
 * and {@code flutter_facebook_auth} both yield an access token — so nothing here is per-platform.
 *
 * <p>One endpoint per provider rather than a {@code {provider}} path variable: the set is two long
 * and fixed, and this way an unknown provider is a 404 from the router instead of a conversion
 * failure we would have to translate.
 */
@RestController
@RequestMapping("/api/v1/auth/oauth")
@RequiredArgsConstructor
@Tag(name = "Social sign-in", description = "Trading a provider token for a session of ours")
public class OAuthController {

    private final OAuthService oauthService;

    @Operation(summary = "Sign in with a Google ID token",
            description = "Creates the account on first use. 401 INVALID_SOCIAL_TOKEN, or "
                    + "401 SOCIAL_LINK_VERIFICATION_REQUIRED when the provider's email already "
                    + "belongs to an account here — a code has been mailed, continue at /google/link.")
    @PostMapping("/google")
    public ApiResponse<SocialLoginResponse> google(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.success(oauthService.login(AuthProvider.GOOGLE, request));
    }

    @Operation(summary = "Sign in with a Facebook access token",
            description = "Same answers as /google, including SOCIAL_LINK_VERIFICATION_REQUIRED.")
    @PostMapping("/facebook")
    public ApiResponse<SocialLoginResponse> facebook(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.success(oauthService.login(AuthProvider.FACEBOOK, request));
    }

    /**
     * Second half of a login answered with {@code SOCIAL_LINK_VERIFICATION_REQUIRED}: the provider
     * token again, plus the code we mailed. Unauthenticated like the login itself — the caller has
     * no session yet, which is the whole point of being here.
     */
    @Operation(summary = "Confirm linking Google to an existing account",
            description = "The provider token again plus the emailed code. 401 INVALID_OTP on a "
                    + "wrong or expired one.")
    @PostMapping("/google/link")
    public ApiResponse<SocialLoginResponse> linkGoogle(@Valid @RequestBody SocialLinkRequest request) {
        return ApiResponse.success(oauthService.confirmLink(AuthProvider.GOOGLE, request));
    }

    @Operation(summary = "Confirm linking Facebook to an existing account",
            description = "Same as /google/link.")
    @PostMapping("/facebook/link")
    public ApiResponse<SocialLoginResponse> linkFacebook(@Valid @RequestBody SocialLinkRequest request) {
        return ApiResponse.success(oauthService.confirmLink(AuthProvider.FACEBOOK, request));
    }
}
