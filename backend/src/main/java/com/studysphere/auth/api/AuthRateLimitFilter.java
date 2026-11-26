package com.studysphere.auth.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public final class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_SOURCES = 4_096;
    private static final AuthProblem TOO_MANY_REQUESTS =
            AuthProblem.of(429, "AUTH_RATE_LIMITED", "Too many authentication requests. Try again later.");

    private final RequestMatcher expensiveOperations;
    private final int requestsPerMinute;
    private final ObjectMapper json;
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true);

    public AuthRateLimitFilter(int requestsPerMinute, ObjectMapper json) {
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException("Authentication requests per minute must be positive.");
        }
        var paths = PathPatternRequestMatcher.withDefaults();
        this.expensiveOperations = new OrRequestMatcher(List.of(
                paths.matcher(HttpMethod.POST, "/api/v1/auth/register"),
                paths.matcher(HttpMethod.POST, "/api/v1/auth/login"),
                paths.matcher(HttpMethod.POST, "/api/v1/auth/forgot-password"),
                paths.matcher(HttpMethod.POST, "/api/v1/auth/reset")
        ));
        this.requestsPerMinute = requestsPerMinute;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !expensiveOperations.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long retryAfter = retryAfter(request.getRemoteAddr());
        if (retryAfter == 0) {
            chain.doFilter(request, response);
            return;
        }

        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        TOO_MANY_REQUESTS.writeTo(response, json);
    }

    private synchronized long retryAfter(String address) {
        long now = System.nanoTime();
        Window window = windows.get(address);
        if (window == null || now - window.startedNanos() >= WINDOW_NANOS) {
            if (window == null && windows.size() == MAX_SOURCES) {
                windows.pollFirstEntry();
            }
            windows.put(address, new Window(now, 1));
            return 0;
        }
        if (window.count() < requestsPerMinute) {
            windows.put(address, new Window(window.startedNanos(), window.count() + 1));
            return 0;
        }
        return Math.ceilDiv(WINDOW_NANOS - (now - window.startedNanos()), TimeUnit.SECONDS.toNanos(1));
    }

    private record Window(long startedNanos, int count) {}
}
