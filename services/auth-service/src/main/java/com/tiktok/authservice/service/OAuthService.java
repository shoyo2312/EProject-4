package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.request.SocialLoginRequest;
import com.tiktok.authservice.dto.response.SocialLoginResponse;
import com.tiktok.authservice.entity.AuthProvider;

public interface OAuthService {

    /**
     * Signs the holder of a provider token in, creating the account the first time. Registration
     * and login are the same call on purpose: the client cannot know which one it is asking for,
     * since whether we have seen this Google account before is a fact only we hold.
     */
    SocialLoginResponse login(AuthProvider provider, SocialLoginRequest request);
}
