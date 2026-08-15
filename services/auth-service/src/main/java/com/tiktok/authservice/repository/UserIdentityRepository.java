package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndProviderUid(AuthProvider provider, String providerUid);
}
