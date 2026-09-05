package com.tiktok.authservice.config;

/**
 * Provisioning for the first ADMIN account. Registration always creates a USER, so without this
 * the only way into the admin console is an UPDATE against the database by hand.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "auth.admin")
public record AdminBootstrapProperties(
        String email,
        String password,
        String username
) {
    public boolean isConfigured() {
        return email != null && !email.isBlank() && password != null && !password.isBlank();
    }
}
