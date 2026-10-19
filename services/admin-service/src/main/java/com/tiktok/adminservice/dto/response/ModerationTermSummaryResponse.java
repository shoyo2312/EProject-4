package com.tiktok.adminservice.dto.response;

/**
 * What the list is made of, for the console's header.
 *
 * @param shippedVersion the {@code version:} of the file the services ship with
 * @param shippedTerms   how many terms that file has
 * @param revision       the admin list's revision; 0 before the first change
 * @param adminTerms     live admin terms, EXEMPT included
 * @param version        what a verdict made with this list records — {@code rules-<shipped>+admin-<revision>}
 */
public record ModerationTermSummaryResponse(
        String shippedVersion,
        int shippedTerms,
        long revision,
        long adminTerms,
        String version
) {
}
