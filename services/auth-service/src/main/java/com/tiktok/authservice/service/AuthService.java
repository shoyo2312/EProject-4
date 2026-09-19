package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.AddEmailRequest;
import com.tiktok.authservice.dto.request.AdminLoginOtpRequest;
import com.tiktok.authservice.dto.request.ForgotPasswordRequest;
import com.tiktok.authservice.dto.request.LoginRequest;
import com.tiktok.authservice.dto.request.RefreshTokenRequest;
import com.tiktok.authservice.dto.request.RegisterRequest;
import com.tiktok.authservice.dto.request.ResendVerificationRequest;
import com.tiktok.authservice.dto.request.ResetPasswordRequest;
import com.tiktok.authservice.dto.request.VerifyEmailRequest;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.dto.response.UserResponse;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    TokenResponse login(LoginRequest request);

    /** Second step of admin-console login: exchanges the emailed OTP (plus a fresh Turnstile
     *  token) for a session. Only reached after {@link #login} answered {@code MFA_REQUIRED}. */
    TokenResponse loginWithOtp(AdminLoginOtpRequest request);

    TokenResponse refresh(RefreshTokenRequest request);

    /**
     * Ends a session. Both tokens are optional and either may be unknown: logout is idempotent,
     * and a client that has lost one of the two still has the other worth spending.
     */
    void logout(String refreshToken, String accessToken);

    UserResponse getCurrentUser(Long userId);

    /**
     * Gives an account created by a social login the address it never had, and sends the code that
     * will prove it. The address only counts once {@link #verifyEmail} accepts that code.
     */
    void addEmail(Long userId, AddEmailRequest request);

    void verifyEmail(VerifyEmailRequest request);

    void resendVerification(ResendVerificationRequest request);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);
}
