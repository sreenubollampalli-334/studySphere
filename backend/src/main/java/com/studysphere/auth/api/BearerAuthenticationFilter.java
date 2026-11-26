package com.studysphere.auth.api;

import java.io.IOException;

import com.studysphere.auth.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_SCHEME = "Bearer ";
    private static final int MAX_HEADER_LENGTH = 512;
    private final AuthService auth;
    private final PathPatternRequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");

    public BearerAuthenticationFilter(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !api.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (headers.hasMoreElements()) {
            String header = headers.nextElement();
            if (!headers.hasMoreElements() && header.length() <= MAX_HEADER_LENGTH
                    && header.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
                auth.authenticate(header.substring(BEARER_SCHEME.length())).ifPresent(identity -> {
                    var authorities = identity.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(identity, null, authorities));
                    SecurityContextHolder.setContext(context);
                });
            }
        }
        chain.doFilter(request, response);
    }
}
