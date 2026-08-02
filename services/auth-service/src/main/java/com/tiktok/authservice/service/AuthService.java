package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
import com.tiktok.authservice.dto.request.VerifyEmailRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    TokenResponse login(LoginRequest request);

    TokenResponse refresh(RefreshTokenRequest request);

    void logout(RefreshTokenRequest request, String accessToken);

    UserResponse getCurrentUser(Long userId);

    void verifyEmail(VerifyEmailRequest request);

    void resendVerification(ResendVerificationRequest request);
}
