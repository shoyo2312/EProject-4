package com.tiktok.common.validation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Where our own media is allowed to live. Read by {@link MediaUrlValidator} in every service
 * that accepts a media URL from a client.
 *
 * <p>Deliberately one shared prefix rather than one per service ({@code app.avatar},
 * {@code app.video}, ...): the answer is the same everywhere — our CDN and our object storage —
 * so per-service copies would only create the chance for one of them to be forgotten and left
 * permissive.
 *
 * <p>Both lists default to empty, and empty means "reject everything". A service that forgets
 * to configure them fails closed and loudly at the first request, instead of quietly accepting
 * any host.
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaUrlProperties(
        /* Hostnames for http(s) media URLs, e.g. the public CDN in front of MinIO. */
        List<String> allowedHosts,
        /* Bucket names for s3:// URLs — in an s3 URI the authority is the bucket, not a host. */
        List<String> allowedBuckets
) {

    public MediaUrlProperties {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        allowedBuckets = allowedBuckets == null ? List.of() : List.copyOf(allowedBuckets);
    }
}
