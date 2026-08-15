package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.request.SocialLoginRequest;
import com.tiktok.authservice.dto.response.SocialLoginResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.service.OAuthService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
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
public class OAuthController {

    private final OAuthService oauthService;

    @PostMapping("/google")
    public ApiResponse<SocialLoginResponse> google(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.success(oauthService.login(AuthProvider.GOOGLE, request));
    }

    @PostMapping("/facebook")
    public ApiResponse<SocialLoginResponse> facebook(@Valid @RequestBody SocialLoginRequest request) {
        return ApiResponse.success(oauthService.login(AuthProvider.FACEBOOK, request));
    }
}
