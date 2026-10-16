package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    Optional<UserSettings> findByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * The rows that exist among {@code userIds} — ids with no row are simply absent, and the
     * caller fills them in with {@link UserSettings#defaultsFor}. One query, so the internal batch
     * endpoint costs the same whether it is asked for one id or a hundred.
     */
    List<UserSettings> findByUserIdInAndDeletedAtIsNull(Collection<Long> userIds);
}
