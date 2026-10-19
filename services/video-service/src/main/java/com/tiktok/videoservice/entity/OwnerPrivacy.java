package com.tiktok.videoservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * video-service's own answer to "is this owner private", for the paths that need it without a
 * video in hand: publishing, the profile grid, the Following feed. Written only by
 * UserPrivacyEventConsumer.
 *
 * <p>An owner with no document is public — right for every account that existed before this did,
 * since none of them could be private.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "owner_privacy")
public class OwnerPrivacy {

    @Id
    private Long userId;

    private boolean privateAccount;

    /** When user-service made the change: an older event arriving later must not undo a newer one. */
    private Instant changedAt;
}
