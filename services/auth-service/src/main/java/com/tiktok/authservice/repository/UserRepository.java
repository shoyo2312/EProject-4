package com.tiktok.authservice.repository;

import com.tiktok.authservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * All lookups are case-insensitive: an account registered as Test@Gmail.com must be reachable by
 * typing test@gmail.com, and two usernames differing only in case must not coexist. The matching
 * unique indexes are on lower(email)/lower(username) — see V6 — so these stay index-backed.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    Optional<User> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    boolean existsByUsernameIgnoreCaseAndDeletedAtIsNull(String username);

    boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(String email);
}
