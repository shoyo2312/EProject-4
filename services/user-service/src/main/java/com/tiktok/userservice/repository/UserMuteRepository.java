package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserMute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserMuteRepository extends JpaRepository<UserMute, Long> {

    Optional<UserMute> findByMuterIdAndMutedIdAndDeletedAtIsNull(Long muterId, Long mutedId);

    Page<UserMute> findByMuterIdAndDeletedAtIsNull(Long muterId, Pageable pageable);
}
