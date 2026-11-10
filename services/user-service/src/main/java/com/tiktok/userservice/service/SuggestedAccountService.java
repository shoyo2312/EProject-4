package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.SuggestedAccountResponse;

import java.util.List;

public interface SuggestedAccountService {

    /**
     * "People you may know" for {@code viewerId}: followers not followed back, then accounts followed
     * by the viewer's followings, then the most followed, each account once under the first tier
     * that found it. A null {@code limit} means 20; any value is clamped to [1, 50].
     */
    List<SuggestedAccountResponse> suggest(Long viewerId, Integer limit);
}
