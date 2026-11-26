package com.studysphere.config;

import jakarta.servlet.DispatcherType;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher;

import java.net.URI;

import com.studysphere.auth.AuthService;
import com.studysphere.auth.api.AuthProblem;
import com.studysphere.auth.api.AuthRateLimitFilter;
import com.studysphere.auth.api.BearerAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    private static final String[] AUTH_MUTATIONS = {
        "/api/v1/auth/register",
        "/api/v1/auth/verify",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset"
    };

    private static final AuthProblem ACCESS_DENIED =
            AuthProblem.of(403, "ACCESS_DENIED", "This request is not permitted.");
    private static final AuthProblem CSRF_REQUIRED =
            AuthProblem.of(403, "CSRF_REQUIRED", "Refresh the CSRF token and retry.");
    private static final AuthProblem AUTHENTICATION_REQUIRED =
            AuthProblem.of(401, "AUTHENTICATION_REQUIRED", "Sign in to continue.");

    @Bean
    CsrfTokenRepository authCsrfTokenRepository(
            @Value("${studysphere.auth.secure-cookies:true}") boolean secureCookies,
            @Value("${studysphere.auth.frontend-origin:http://localhost:5173}") String frontendOrigin) {
        if (!secureCookies) {
            String host = URI.create(frontendOrigin).getHost();
            if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host) && !"[::1]".equals(host)) {
                throw new IllegalArgumentException("Disabling secure auth cookies requires a loopback frontend origin.");
            }
        }
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.path("/").sameSite("Lax").secure(secureCookies));
        return repository;
    }

    @Bean
    @Order(1)
    SecurityFilterChain authSecurityFilterChain(HttpSecurity http, CsrfTokenRepository csrf, ObjectMapper json,
            @Value("${studysphere.auth.requests-per-minute:60}") int requestsPerMinute)
            throws Exception {
        return stateless(http.securityMatcher("/api/v1/auth/**"))
                .csrf(config -> config.spa().csrfTokenRepository(csrf))
                .addFilterAfter(new AuthRateLimitFilter(requestsPerMinute, json), CsrfFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) -> ACCESS_DENIED.writeTo(response, json))
                        .accessDeniedHandler((request, response, failure) ->
                                (failure instanceof CsrfException ? CSRF_REQUIRED : ACCESS_DENIED)
                                        .writeTo(response, json)))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, AUTH_MUTATIONS).permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService auth, ObjectMapper json) throws Exception {
        var apiPaths = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**");
        return stateless(http)
                // Routes in this chain authorize with bearer credentials only.
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) -> {
                            if (apiPaths.matches(request)) {
                                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                                AUTHENTICATION_REQUIRED.writeTo(response, json);
                            } else {
                                ACCESS_DENIED.writeTo(response, json);
                            }
                        })
                        .accessDeniedHandler((request, response, failure) -> ACCESS_DENIED.writeTo(response, json)))
                .addFilterBefore(new BearerAuthenticationFilter(auth), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/ws/messages").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/analytics/overview", "/api/v1/achievements",
                                "/api/v1/me/streak", "/api/v1/notifications", "/api/v1/me/notification-preferences").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/me/notification-preferences",
                                "/api/v1/notifications/{id}/read").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms/{id}/assistant",
                                "/api/v1/rooms/{id}/notes").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/rooms/{id}/assistant/consent").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/rooms/{id}/notes",
                                "/api/v1/rooms/{id}/summaries",
                                "/api/v1/rooms/{id}/assistant/proposals/{proposalId}/approve").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/rooms/{id}/notes/{noteId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/communities", "/api/v1/communities/{id}",
                                "/api/v1/communities/{id}/members",
                                "/api/v1/posts", "/api/v1/posts/{id}/comments", "/api/v1/posts/{id}/related",
                                "/api/v1/communities/{id}/resources",
                                "/api/v1/communities/{id}/resources/{resourceId}/download").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/communities", "/api/v1/posts",
                                "/api/v1/posts/{id}/comments", "/api/v1/posts/{id}/report",
                                "/api/v1/posts/{id}/comments/{commentId}/report",
                                "/api/v1/communities/{id}/summaries").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/communities/{id}/members/{userId}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/communities/{id}/members/me",
                                "/api/v1/posts/{id}/vote", "/api/v1/communities/{id}/resources/{resourceId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/communities/{id}/members/me", "/api/v1/posts/{id}",
                                "/api/v1/posts/{id}/comments/{commentId}", "/api/v1/posts/{id}/vote",
                                "/api/v1/communities/{id}/resources/{resourceId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/analytics/topics", "/api/v1/recommendations",
                                "/api/v1/plans/proposals", "/api/v1/plans/proposals/{id}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/recommendations/{id}/feedback",
                                "/api/v1/plans/proposals", "/api/v1/plans/proposals/{id}/approve").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/quizzes", "/api/v1/quizzes/{id}",
                                "/api/v1/quizzes/{id}/attempts", "/api/v1/attempts/{id}",
                                "/api/v1/flashcard-decks", "/api/v1/flashcard-decks/{id}",
                                "/api/v1/flashcards/due", "/api/v1/flashcards/{id}/reviews").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/quizzes", "/api/v1/quizzes/generate",
                                "/api/v1/quizzes/{id}/attempts", "/api/v1/attempts/{id}/submit",
                                "/api/v1/flashcard-decks", "/api/v1/flashcard-decks/generate",
                                "/api/v1/flashcard-decks/{id}/cards", "/api/v1/flashcards/{id}/reviews").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/flashcard-decks/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/documents",
                                "/api/v1/documents/{id}/status").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/documents", "/api/v1/documents/{id}/retry",
                                "/api/v1/documents/{id}/ask").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/documents/{id}").authenticated()
                        .requestMatchers(new AndRequestMatcher(new DispatcherTypeRequestMatcher(DispatcherType.ASYNC),
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST,
                                        "/api/v1/ai/conversations/{id}/stream"))).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ai/conversations",
                                "/api/v1/ai/conversations/{id}", "/api/v1/ai/conversations/{id}/messages").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai/conversations",
                                "/api/v1/ai/conversations/{id}/stream").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/ai/conversations/{id}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/ai/conversations/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/rooms", "/api/v1/rooms/{id}",
                                "/api/v1/room-invitations", "/api/v1/rooms/{id}/members",
                                "/api/v1/rooms/{id}/invitations", "/api/v1/rooms/{id}/agenda",
                                "/api/v1/rooms/{id}/resources", "/api/v1/rooms/{id}/resources/{resourceId}/download",
                                "/api/v1/rooms/{id}/messages").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/rooms", "/api/v1/rooms/{id}/invitations",
                                "/api/v1/rooms/{id}/agenda", "/api/v1/rooms/{id}/resources",
                                "/api/v1/rooms/{id}/messages").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/rooms/{id}",
                                "/api/v1/rooms/{id}/members/{userId}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/rooms/{id}/members/me",
                                "/api/v1/rooms/{id}/messages/{messageId}/pin").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/rooms/{id}",
                                "/api/v1/rooms/{id}/members/{userId}", "/api/v1/rooms/{id}/invitations/{userId}",
                                "/api/v1/rooms/{id}/agenda/{itemId}", "/api/v1/rooms/{id}/resources/{resourceId}",
                                "/api/v1/rooms/{id}/messages/{messageId}/pin").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/partners", "/api/v1/connections",
                                "/api/v1/connections/requests", "/api/v1/blocks").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/connections/requests").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/connections/requests/{id}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/blocks/{userId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/connections/{userId}",
                                "/api/v1/blocks/{userId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/conversations",
                                "/api/v1/conversations/{id}/messages",
                                "/api/v1/conversations/{id}/messages/{messageId}/attachment").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/conversations",
                                "/api/v1/conversations/{id}/messages", "/api/v1/conversations/{id}/read",
                                "/api/v1/conversations/{id}/messages/{messageId}/report").authenticated()
                        .requestMatchers(HttpMethod.DELETE,
                                "/api/v1/conversations/{id}/messages/{messageId}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/me").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/me").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/me/profile", "/api/v1/me/preferences", "/api/v1/me/availability",
                                "/api/v1/me/subjects", "/api/v1/me/goals", "/api/v1/subjects",
                                "/api/v1/subjects/{id}/topics", "/api/v1/dashboard").authenticated()
                        .requestMatchers(HttpMethod.PUT,
                                "/api/v1/me/profile", "/api/v1/me/preferences",
                                "/api/v1/me/availability", "/api/v1/me/subjects").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/me/goals").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/me/goals/{id}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/me/goals/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/plans", "/api/v1/plans/{id}",
                                "/api/v1/tasks", "/api/v1/tasks/{id}", "/api/v1/tasks/{id}/reminders",
                                "/api/v1/exams", "/api/v1/exams/{id}", "/api/v1/focus/sessions",
                                "/api/v1/focus/sessions/{id}", "/api/v1/focus/summary").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/plans", "/api/v1/tasks",
                                "/api/v1/tasks/{id}/reminders", "/api/v1/exams", "/api/v1/focus/sessions",
                                "/api/v1/focus/sessions/{id}/complete").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/plans/{id}", "/api/v1/tasks/{id}",
                                "/api/v1/exams/{id}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/plans/{id}", "/api/v1/tasks/{id}",
                                "/api/v1/tasks/{id}/reminders/{reminderId}", "/api/v1/exams/{id}").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/resources", "/api/v1/resources/recent",
                                "/api/v1/resources/{id}", "/api/v1/resources/{id}/download",
                                "/api/v1/resources/{id}/permissions", "/api/v1/collections",
                                "/api/v1/folders", "/api/v1/bookmarks").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/resources", "/api/v1/resources/{id}/permissions",
                                "/api/v1/collections", "/api/v1/folders").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/resources/{id}").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/bookmarks/{resourceId}",
                                "/api/v1/collections/{id}/resources/{resourceId}").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/resources/{id}",
                                "/api/v1/resources/{id}/permissions/{userId}", "/api/v1/collections/{id}",
                                "/api/v1/folders/{id}", "/api/v1/bookmarks/{resourceId}",
                                "/api/v1/collections/{id}/resources/{resourceId}").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    private HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
    }
}
