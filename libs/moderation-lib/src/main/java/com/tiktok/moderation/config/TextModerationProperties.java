package com.tiktok.moderation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * @param enforce              false computes, logs and counts every verdict but holds and refuses
 *                             nothing — for measuring what the rules would catch on real traffic
 *                             before they are allowed to act on it
 * @param blocklist            where the term list is read from at startup
 * @param captionLinkAllowlist domains a video's own caption may link to without review: creators
 *                             point at their other profiles. Subdomains count ({@code m.facebook.com}).
 *                             Comments get no allow-list
 * @param mentionFlood         this many {@code @mentions} in one comment or description is spam
 * @param classifier           moderation-service's text classifier, behind the rules
 * @param adminTerms           admin-service's terms, on top of the shipped list
 */
@ConfigurationProperties(prefix = "moderation.text")
public record TextModerationProperties(
        @DefaultValue("true") boolean enforce,
        @DefaultValue("classpath:moderation/blocklist-vi.txt") String blocklist,
        @DefaultValue({"youtube.com", "youtu.be", "facebook.com", "fb.com", "instagram.com", "tiktok.com",
                "x.com", "twitter.com", "threads.net"}) List<String> captionLinkAllowlist,
        @DefaultValue("5") int mentionFlood,
        @DefaultValue Classifier classifier,
        @DefaultValue AdminTerms adminTerms
) {

    /**
     * @param enabled  false never asks; neither does a blank {@code baseUrl}
     * @param baseUrl  moderation-service, e.g. {@code http://localhost:8099}
     * @param timeout  connect and read, each. A comment waits on this, so it is short: a classifier
     *                 slower than this is treated as down, and the rules decide alone
     * @param enforce  false records the model's HOLD as a note on the comment or video and acts on
     *                 nothing — for measuring its precision on this platform's text first
     * @param cooldown how long to stop asking after a failure
     */
    public record Classifier(
            @DefaultValue("true") boolean enabled,
            String baseUrl,
            @DefaultValue("1s") Duration timeout,
            @DefaultValue("true") boolean enforce,
            @DefaultValue("30s") Duration cooldown
    ) {
    }

    /**
     * @param baseUrl admin-service, e.g. {@code http://localhost:8096}. Blank: the shipped list only
     * @param refresh how often to ask whether the list changed
     * @param timeout connect and read, each
     */
    public record AdminTerms(
            String baseUrl,
            @DefaultValue("60s") Duration refresh,
            @DefaultValue("2s") Duration timeout
    ) {
    }
}
