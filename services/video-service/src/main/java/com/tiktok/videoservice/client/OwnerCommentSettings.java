package com.tiktok.videoservice.client;

import java.util.List;

/**
 * An account's settings as user-service holds them, both the ones it has as a video owner and the
 * one it has as a viewer: who may comment and the filters interaction-service applies to comments
 * under its videos (relayed through {@code /policy}, never interpreted here — the names are passed
 * on as user-service spells them), and the keywords it filters out of its own feeds. One read and
 * one cache entry serve both, so the cache TTL ({@code app.cache.user-settings.ttl}, 3s) is also how
 * soon a new keyword filter shows.
 *
 * <p>Each field is null when user-service did not send it: one deployed before the field existed.
 * interaction-service reads a null as that setting's default, and a null {@code feedKeywords} is an
 * unfiltered feed.
 */
public record OwnerCommentSettings(
        String commentAudience,
        Boolean filterSpamOffensive,
        Boolean filterAllComments,
        List<String> commentKeywords,
        FeedKeywords feedKeywords
) {
}
