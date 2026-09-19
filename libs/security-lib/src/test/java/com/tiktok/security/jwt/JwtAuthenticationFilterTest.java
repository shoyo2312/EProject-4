package com.tiktok.security.jwt;

import com.tiktok.crypto.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private final JwtProvider provider = new JwtProvider("test-secret-at-least-32-bytes-long-0123456789");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAccessToken_authenticatesTheCallerWithTheirRole() throws Exception {
        run(new JwtAuthenticationFilter(provider, claims -> false),
                token(JwtProvider.TOKEN_TYPE_ACCESS, Map.of("role", "ADMIN")));

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getPrincipal()).isEqualTo(7L);
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test
    void aRefreshToken_authenticatesNobody() throws Exception {
        run(new JwtAuthenticationFilter(provider, claims -> false), token(JwtProvider.TOKEN_TYPE_REFRESH, Map.of()));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aRevokedToken_authenticatesNobodyButTheRequestStillProceeds() throws Exception {
        MockFilterChain chain = run(new JwtAuthenticationFilter(provider, claims -> true),
                token(JwtProvider.TOKEN_TYPE_ACCESS, Map.of()));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void noHeader_isAnonymous() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new JwtAuthenticationFilter(provider, claims -> false)
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    private String token(String type, Map<String, Object> extra) {
        Map<String, Object> claims = new java.util.HashMap<>(extra);
        claims.put(JwtProvider.CLAIM_TOKEN_TYPE, type);
        return provider.generateToken("7", claims, 60_000);
    }

    private MockFilterChain run(JwtAuthenticationFilter filter, String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
