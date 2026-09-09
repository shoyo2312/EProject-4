package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.mapper.UserMapper;
import com.tiktok.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the admin console's user list. It lives here rather than in admin-service because
 * this is the service that owns {@code status} and {@code role} — admin-service cannot read this
 * database, and proxying the query over HTTP would buy nothing but a second failure mode.
 */
@Service
@RequiredArgsConstructor
public class AdminUserDirectory {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public Page<UserResponse> search(String query, UserStatus status, Pageable pageable) {
        String term = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase() + "%";

        return userRepository.searchForAdmin(status, term, pageable).map(userMapper::toResponse);
    }
}
