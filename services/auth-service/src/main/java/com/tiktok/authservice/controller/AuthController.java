package com.tiktok.authservice.controller;

import com.tiktok.authservice.dto.request.ForgotPasswordRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
import com.tiktok.authservice.dto.request.ResetPasswordRequest;
import com.tiktok.authservice.dto.request.VerifyEmailRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.service.AuthService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request,
                        @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String accessToken = authorizationHeader != null && authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length())
                : null;
        authService.logout(request, accessToken);
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(authService.getCurrentUser(userId));
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        authService.verifyEmail(request);
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request);
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
    }
}
