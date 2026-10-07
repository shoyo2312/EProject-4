package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.UserResponse;

public interface UsernameChangeService {

    /**
     * Gives the account a new handle, at most once every 30 days — see {@link UsernameCooldown}.
     *
     * <p>The account's current handle is not a change: it answers as is and spends nothing, so a
     * client that sends the field untouched is not penalised for it.
     */
    UserResponse changeUsername(Long userId, String username);
}
