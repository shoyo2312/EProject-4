package com.tiktok.adminservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Whether an account is an admin, asked of auth-service — the only service that knows roles.
 *
 * <p>auth-service refuses to ban an admin account, and the refusal reached nobody: the audit log
 * here said "banned", the reports against the account closed as handled, and video-service and
 * search-service went on to hide its videos for a ban that never landed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRoleClient {

    private final RestClient authServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Account data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(String role) {
    }

    /**
     * Asked with the console's own token: the endpoint is ROLE_ADMIN, the caller is an admin, and
     * there is no service credential. Fails open — auth-service unreachable answers "not an admin" —
     * because auth-service still refuses an admin ban on its side, and a ban held up by an outage
     * of the service that has to enforce it would be held up for nothing.
     */
    public boolean isAdmin(Long userId) {
        String authorization = currentAuthorization();
        if (authorization == null) {
            log.warn("No caller token to ask auth-service whether user {} is an admin; assuming not", userId);
            return false;
        }
        try {
            Envelope envelope = authServiceRestClient.get()
                    .uri("/api/v1/auth/admin/users/{id}", userId)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(Envelope.class);
            return envelope != null && envelope.data() != null && "ADMIN".equals(envelope.data().role());
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            log.warn("Could not ask auth-service whether user {} is an admin; assuming not: {}",
                    userId, e.getMessage());
            return false;
        }
    }

    private static String currentAuthorization() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION)
                : null;
    }
}
