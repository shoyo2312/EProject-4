package com.tiktok.storyservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Which owners are private: their PUBLIC stories are for followers only. Written only by
 * UserPrivacyEventConsumer. An owner with no document is public.
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
