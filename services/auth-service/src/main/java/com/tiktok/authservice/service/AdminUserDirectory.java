package com.tiktok.authservice.service;

import com.tiktok.authservice.dto.response.AdminUserResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.mapper.UserMapper;
import com.tiktok.authservice.repository.UserIdentityRepository;
import com.tiktok.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read side of the admin console's user list. It lives here rather than in admin-service because
 * this is the service that owns {@code status} and {@code role} — admin-service cannot read this
 * database, and proxying the query over HTTP would buy nothing but a second failure mode.
 */
@Service
@RequiredArgsConstructor
public class AdminUserDirectory {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> search(String query, UserStatus status, Pageable pageable) {
        String term = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase() + "%";

        Page<User> users = userRepository.searchForAdmin(status, term, pageable);

        // One query for the whole page's provider links rather than one per row.
        List<Long> ids = users.map(User::getId).getContent();
        Map<Long, List<AuthProvider>> providersByUser = ids.isEmpty()
                ? Map.of()
                : userIdentityRepository.findByUserIdInOrderByCreatedAtAsc(ids).stream()
                        .collect(Collectors.groupingBy(
                                identity -> identity.getUserId(),
                                Collectors.mapping(identity -> identity.getProvider(), Collectors.toList())));

        return users.map(user -> userMapper.toAdminResponse(
                user, providersByUser.getOrDefault(user.getId(), List.of())));
    }
}
