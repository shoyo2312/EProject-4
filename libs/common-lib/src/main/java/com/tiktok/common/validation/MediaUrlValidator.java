package com.tiktok.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Rejects any media URL that does not point at storage we own.
 *
 * <p>Two checks, and both are needed. The scheme check throws out {@code javascript:},
 * {@code data:} and {@code file:} — payloads that are dangerous the moment a client renders
 * them, whatever the host says. The allow-list check throws out well-formed URLs on hosts that
 * are simply not ours, which is what stops a stored URL from becoming a request our own backend
 * makes to an attacker's server, or a tracking pixel we serve to every viewer of a profile.
 *
 * <p>An {@code s3://} URI is treated differently on purpose: its authority is a bucket name, not
 * a hostname, so it is matched against the bucket list. Checking it against
 * {@code allowedHosts} would compare a bucket to a CDN domain and reject everything.
 *
 * <p>Spring's SpringConstraintValidatorFactory autowires this through the ApplicationContext
 * (no {@code @Component} needed) whenever LocalValidatorFactoryBean instantiates it.
 */
@RequiredArgsConstructor
public class MediaUrlValidator implements ConstraintValidator<ValidMediaUrl, String> {

    private static final String S3_SCHEME = "s3";

    private final MediaUrlProperties mediaUrlProperties;

    private String[] allowedSchemes;

    @Override
    public void initialize(ValidMediaUrl constraint) {
        this.allowedSchemes = constraint.schemes();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }

        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !isAllowedScheme(scheme)) {
            return false;
        }

        // Null for schemes that carry no authority (javascript:, data:, mailto:) and for
        // authorities that are not a valid host — either way there is nothing to allow-list.
        String authority = uri.getHost();
        if (authority == null) {
            return false;
        }

        List<String> allowed = S3_SCHEME.equalsIgnoreCase(scheme)
                ? mediaUrlProperties.allowedBuckets()
                : mediaUrlProperties.allowedHosts();

        return allowed.stream().anyMatch(authority::equalsIgnoreCase);
    }

    private boolean isAllowedScheme(String scheme) {
        String normalized = scheme.toLowerCase(Locale.ROOT);
        return Arrays.stream(allowedSchemes)
                .anyMatch(allowed -> allowed.toLowerCase(Locale.ROOT).equals(normalized));
    }
}
