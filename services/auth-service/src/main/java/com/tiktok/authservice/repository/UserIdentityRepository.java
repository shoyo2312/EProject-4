package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndProviderUid(AuthProvider provider, String providerUid);

    /** Admin console: hydrate the provider links for a page of users in one query. */
    List<UserIdentity> findByUserIdInOrderByCreatedAtAsc(Collection<Long> userIds);

    List<UserIdentity> findByUserIdOrderByCreatedAtAsc(Long userId);

    /** An account's deletion ran: its provider accounts may sign up again as new accounts. */
    @Modifying(flushAutomatically = true)
    @Query("delete from UserIdentity i where i.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    /**
     * Flags every link on an account whose address was never proven — which is every link it has.
     * Returns how many were flagged. See {@code IdentityReconfirmation}.
     */
    @Modifying(flushAutomatically = true)
    @Query("update UserIdentity i set i.needsReconfirmation = true "
            + "where i.userId = :userId and i.needsReconfirmation = false")
    int requireReconfirmationOfAll(@Param("userId") Long userId);

    /**
     * Flags the links attached before the address was proven at {@code provenAt}. A link made
     * afterwards was made against a proven address — a link code, or a provider vouching for it —
     * and is left alone.
     */
    @Modifying(flushAutomatically = true)
    @Query("update UserIdentity i set i.needsReconfirmation = true "
            + "where i.userId = :userId and i.needsReconfirmation = false and i.createdAt < :provenAt")
    int requireReconfirmationOfLinkedBefore(@Param("userId") Long userId, @Param("provenAt") Instant provenAt);
}
