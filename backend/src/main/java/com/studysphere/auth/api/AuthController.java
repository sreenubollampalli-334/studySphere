package com.studysphere.auth.api;

import java.time.Duration;
import java.util.List;

import com.studysphere.auth.AuthService;
import com.studysphere.auth.AuthService.Identity;
import com.studysphere.auth.AuthService.Session;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuthController {

    private static final String REFRESH_COOKIE = "studysphere_refresh";
    private static final String AUTH_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final CsrfTokenRepository csrf;
    private final boolean secureCookies;

    AuthController(AuthService auth, CsrfTokenRepository csrf,
                   @Value("${studysphere.auth.secure-cookies:true}") boolean secureCookies) {
        this.auth = auth;
        this.csrf = csrf;
        this.secureCookies = secureCookies;
    }

    @GetMapping("/auth/csrf")
    ResponseEntity<Void> csrf() {
        // The SPA CSRF handler materializes the token cookie while handling this request.
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = "/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> register(@RequestBody Credentials credentials) {
        auth.register(credentials.email(), credentials.password());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = "/auth/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> verify(@RequestBody TokenRequest token) {
        auth.verify(token.token());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionResponse> login(@RequestBody Credentials credentials, HttpServletRequest request,
                                          HttpServletResponse response) {
        Session session = auth.login(credentials.email(), credentials.password());
        rotateCsrf(request, response);
        return sessionResponse(session);
    }

    @PostMapping("/auth/refresh")
    ResponseEntity<SessionResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String token) {
        return sessionResponse(auth.refresh(token));
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String token,
                                HttpServletRequest request, HttpServletResponse response) {
        auth.logout(token);
        rotateCsrf(request, response);
        return clearSession();
    }

    @PostMapping(path = "/auth/forgot-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> forgotPassword(@RequestBody EmailRequest email) {
        auth.forgotPassword(email.email());
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = "/auth/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> resetPassword(@RequestBody ResetRequest reset) {
        auth.resetPassword(reset.token(), reset.newPassword());
        return clearSession();
    }

    @GetMapping("/me")
    ResponseEntity<AccountResponse> account(@AuthenticationPrincipal Identity identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AccountResponse(Long.toString(identity.id()), identity.email(), identity.roles()));
    }

    @DeleteMapping("/me")
    ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal Identity identity, HttpServletRequest request,
                                       HttpServletResponse response) {
        auth.delete(identity);
        rotateCsrf(request, response);
        return clearSession();
    }

    private void rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(csrf.generateToken(request), request, response);
    }

    private ResponseEntity<SessionResponse> sessionResponse(Session session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken(), session.refreshExpiresInSeconds()))
                .body(new SessionResponse(session.accessToken(), session.expiresInSeconds()));
    }

    private ResponseEntity<Void> clearSession() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0))
                .build();
    }

    private String refreshCookie(String token, long ttlSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(secureCookies)
                .sameSite("Lax")
                .path(AUTH_PATH)
                .maxAge(Duration.ofSeconds(ttlSeconds))
                .build()
                .toString();
    }

    record Credentials(String email, String password) {}
    record TokenRequest(String token) {}
    record EmailRequest(String email) {}
    record ResetRequest(String token, String newPassword) {}
    record SessionResponse(String accessToken, long expiresInSeconds) {}
    record AccountResponse(String id, String email, List<String> roles) {}
}
