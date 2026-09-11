package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndProviderUid(AuthProvider provider, String providerUid);

    /** Admin console: hydrate the provider links for a page of users in one query. */
    List<UserIdentity> findByUserIdInOrderByCreatedAtAsc(Collection<Long> userIds);
}
