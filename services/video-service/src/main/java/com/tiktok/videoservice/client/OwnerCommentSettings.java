package com.tiktok.videoservice.client;

import java.util.List;

/**
 * A video owner's comment settings, as user-service holds them: who may comment, and the filters
 * interaction-service applies to comments under their videos. Relayed through {@code /policy}, never
 * interpreted here — the names are passed on as user-service spells them.
 *
 * <p>Each field is null when user-service did not send it: one deployed before the field existed.
 * interaction-service reads a null as that setting's default.
 */
public record OwnerCommentSettings(
        String commentAudience,
        Boolean filterSpamOffensive,
        Boolean filterAllComments,
        List<String> commentKeywords
) {
}
