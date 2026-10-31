package com.tiktok.security.internal;

import com.tiktok.common.http.InternalApiToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Admits a request that carries the internal API token — for endpoints only other services call:
 * {@code .requestMatchers("/api/v1/users/internal/**").access(new InternalCallerAuthorization(token))}.
 * Leave the HTTP method out of the matcher: Spring MVC answers a HEAD with the GET handler, so a
 * GET-only rule lets a HEAD carrying a user's JWT fall through to the next rule.
 * See {@link InternalApiToken} for why the gateway refusing those paths is not enough.
 */
public class InternalCallerAuthorization implements AuthorizationManager<RequestAuthorizationContext> {

    private final InternalApiToken token;

    public InternalCallerAuthorization(InternalApiToken token) {
        this.token = token;
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication,
                                           RequestAuthorizationContext context) {
        return new AuthorizationDecision(token.accepts(context.getRequest().getHeader(InternalApiToken.HEADER)));
    }
}
