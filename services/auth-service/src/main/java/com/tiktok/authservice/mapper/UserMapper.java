package com.tiktok.authservice.mapper;

import com.tiktok.authservice.dto.response.AdminUserResponse;
import com.tiktok.authservice.dto.response.UserResponse;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);

    /**
     * Admin console row. {@code linkedProviders} is a separate argument because it comes from
     * {@code user_identities}, not the {@code User} row; {@code provider} is just its first
     * element, precomputed so the console can branch on "social account?" without unpacking a list.
     */
    @Mapping(target = "provider",
            expression = "java(linkedProviders == null || linkedProviders.isEmpty() ? null : linkedProviders.get(0))")
    @Mapping(target = "linkedProviders", source = "linkedProviders")
    AdminUserResponse toAdminResponse(User user, List<AuthProvider> linkedProviders);
}
