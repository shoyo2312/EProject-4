package com.tiktok.userservice.repository;

import com.tiktok.userservice.entity.UserMute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Every query here starts from the muter, which is why {@code user_mutes} is indexed on
 * {@code muter_id} alone while V5 indexed {@code user_blocks} in both directions — blocks are
 * read from the blocked side too, when deciding whether a viewer may see a profile. The missing
 * {@code muted_id} index is the schema matching the queries, not an oversight.
 *
 * <p>Anything answering "who muted me" would scan the table. V6 has been applied, so its checksum
 * is fixed: that index belongs in a new migration, not an edit to the old one.
 */
public interface UserMuteRepository extends JpaRepository<UserMute, Long> {

    Optional<UserMute> findByMuterIdAndMutedIdAndDeletedAtIsNull(Long muterId, Long mutedId);

    Page<UserMute> findByMuterIdAndDeletedAtIsNull(Long muterId, Pageable pageable);
}
