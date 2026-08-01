package com.tiktok.userservice.validation;

import com.tiktok.userservice.config.AvatarProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * Only https URLs on a configured allow-list (app.avatar.allowed-hosts, i.e. our own
 * MinIO/CDN host) are accepted, so users can't point their avatar at an arbitrary
 * external/malicious URL. Spring's SpringConstraintValidatorFactory autowires this via
 * the ApplicationContext (no @Component needed) whenever the LocalValidatorFactoryBean
 * instantiates it.
 */
@RequiredArgsConstructor
public class AvatarUrlValidator implements ConstraintValidator<ValidAvatarUrl, String> {

    private final AvatarProperties avatarProperties;

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

        if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
            return false;
        }

        List<String> allowedHosts = avatarProperties.allowedHosts();
        return allowedHosts != null && allowedHosts.stream().anyMatch(uri.getHost()::equalsIgnoreCase);
    }
}
