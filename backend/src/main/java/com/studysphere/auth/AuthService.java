package com.studysphere.auth;

import com.studysphere.admin.AccountRestrictions;

import static com.studysphere.auth.AuthStore.TokenPurpose.RESET;
import static com.studysphere.auth.AuthStore.TokenPurpose.VERIFY;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    private static final Duration REFRESH_IDLE = Duration.ofDays(7);
    private static final Duration REFRESH_ABSOLUTE = Duration.ofDays(30);
    private static final Duration VERIFY_LIFETIME = Duration.ofHours(24);
    private static final Duration RESET_LIFETIME = Duration.ofHours(1);
    private static final Duration REAUTH_WINDOW = Duration.ofMinutes(5);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String EMAIL_DOMAIN_LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-z0-9.!#$%&'*+/=?^_\\x60{|}~-]{1,64}@" + EMAIL_DOMAIN_LABEL + "(?:\\." + EMAIL_DOMAIN_LABEL + ")*"
    );

    private final AuthStore store;
    private final AccountRestrictions restrictions;
    private final AuthMailer mailer;
    private final ApplicationEventPublisher events;
    private final URI frontendOrigin;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    private final Pbkdf2PasswordEncoder passwords = new Pbkdf2PasswordEncoder(
            "", 16, 600_000, Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256
    );
    private final String dummyPasswordHash = passwords.encode(randomToken());

    AuthService(
            AuthStore store,
            AccountRestrictions restrictions,
            AuthMailer mailer,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            @Value("${studysphere.auth.frontend-origin:http://localhost:5173}") String configuredFrontendOrigin
    ) {
        this.store = store;
        this.restrictions = restrictions;
        this.mailer = mailer;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.frontendOrigin = frontendOrigin(configuredFrontendOrigin);
    }

    public void register(String email, String password) {
        String canonical = canonicalEmail(email);
        requireNewPassword(password);
        String passwordHash = passwords.encode(password);
        String verification;

        try {
            verification = transactions.execute(status -> {
                Instant now = now();
                long userId = store.createStudent(canonical, passwordHash, now);
                return issueOneUse(VERIFY, userId, now, VERIFY_LIFETIME);
            });
        } catch (DuplicateKeyException duplicate) {
            verification = reissuePendingVerification(canonical, password);
        }

        if (verification != null) {
            mailer.sendVerification(canonical, emailLink("/verify-email", verification));
        }
    }

    public void verify(String token) {
        byte[] hash = presentedHash(token).orElseThrow(AuthService::invalidVerification);
        transactions.executeWithoutResult(status -> {
            long userId = store.oneUseOwner(VERIFY, hash).orElseThrow(AuthService::invalidVerification);
            AuthStore.User user = store.lockUser(userId).orElseThrow(AuthService::invalidVerification);
            Instant now = now();
            if (!user.pending() || !usable(VERIFY, hash, now)) {
                throw invalidVerification();
            }

            store.activateUser(userId, now);
            store.consumeOneUseTokens(VERIFY, userId, now);
        });
    }

    public Session login(String email, String password) {
        String canonical = canonicalEmail(email);
        if (!acceptableLoginPassword(password)) {
            throw denied(401, "INVALID_CREDENTIALS", "The email or password is incorrect.");
        }

        AuthStore.User snapshot = store.findUser(canonical).orElse(null);
        String encoded = snapshot == null ? dummyPasswordHash : snapshot.passwordHash();
        if (!passwords.matches(password, encoded) || snapshot == null) {
            throw denied(401, "INVALID_CREDENTIALS", "The email or password is incorrect.");
        }

        return transactions.execute(status -> {
            AuthStore.User user = store.lockUser(snapshot.id())
                    .filter(account -> snapshot.passwordHash().equals(account.passwordHash()))
                    .orElseThrow(() -> denied(401, "INVALID_CREDENTIALS", "The email or password is incorrect."));
            if (!user.active()) {
                throw denied(403, "EMAIL_VERIFICATION_REQUIRED", "Verify your email before signing in.");
            }
            if (restrictions.active(user.id())) {
                throw denied(403, "ACCOUNT_RESTRICTED", "This account is temporarily restricted.");
            }

            Instant now = now();
            return issueSession(user.id(), randomBytes(16), null, now, now.plus(REFRESH_ABSOLUTE), now);
        });
    }

    public Session refresh(String refreshToken) {
        byte[] hash = presentedHash(refreshToken).orElseThrow(AuthService::invalidRefresh);
        Session session = transactions.execute(status -> rotate(hash));
        if (session == null) {
            // Finish the transaction before replying with 401 so a replay revocation is durable.
            throw invalidRefresh();
        }
        return session;
    }

    public void logout(String refreshToken) {
        byte[] hash = presentedHash(refreshToken).orElse(null);
        if (hash == null) {
            return;
        }

        transactions.executeWithoutResult(status -> {
            AuthStore.User user = store.refreshOwner(hash).flatMap(store::lockUser).orElse(null);
            if (user != null) {
                store.lockRefresh(hash).ifPresent(refresh -> store.revokeFamily(user.id(), refresh.family(), now()));
            }
        });
    }

    public void forgotPassword(String email) {
        String canonical = canonicalEmail(email);
        String reset = transactions.execute(status -> store.findUser(canonical)
                .flatMap(user -> store.lockUser(user.id()))
                .filter(AuthStore.User::active)
                .map(user -> issueOneUse(RESET, user.id(), now(), RESET_LIFETIME))
                .orElse(null));
        if (reset != null) {
            mailer.sendPasswordReset(canonical, emailLink("/reset-password", reset));
        }
    }

    public void resetPassword(String token, String newPassword) {
        byte[] hash = presentedHash(token).orElseThrow(AuthService::invalidReset);
        requireNewPassword(newPassword);
        long userId = store.oneUseOwner(RESET, hash).orElseThrow(AuthService::invalidReset);
        String passwordHash = passwords.encode(newPassword);

        transactions.executeWithoutResult(status -> {
            AuthStore.User user = store.lockUser(userId).orElseThrow(AuthService::invalidReset);
            Instant now = now();
            if (!user.active() || !usable(RESET, hash, now)) {
                throw invalidReset();
            }

            store.changePassword(userId, passwordHash, now);
            store.consumeOneUseTokens(RESET, userId, now);
            store.revokeAllSessions(userId, now);
        });
    }

    public Optional<Identity> authenticate(String accessToken) {
        return presentedHash(accessToken)
                .flatMap(hash -> store.findBearerUser(hash, now()))
                .filter(user -> !restrictions.active(user.id()))
                .flatMap(user -> {
                    List<String> roles = store.roles(user.id());
                    if (roles.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Identity(user.id(), user.email(), roles, user.passwordAuthenticatedAt()));
                });
    }

    public void delete(Identity identity) {
        if (!identity.passwordAuthenticatedAt().plus(REAUTH_WINDOW).isAfter(now())) {
            throw denied(403, "REAUTHENTICATION_REQUIRED", "Sign in again before deleting your account.");
        }

        transactions.executeWithoutResult(status -> {
            store.lockUser(identity.id()).filter(AuthStore.User::active)
                    .orElseThrow(() -> denied(401, "AUTHENTICATION_REQUIRED", "Sign in to continue."));
            events.publishEvent(new AccountDeletionRequested(identity.id()));
            store.deleteUser(identity.id());
        });
    }

    private Session rotate(byte[] hash) {
        AuthStore.User user = store.refreshOwner(hash).flatMap(store::lockUser).orElse(null);
        if (user == null || !user.active() || restrictions.active(user.id())) {
            return null;
        }
        AuthStore.Refresh refresh = store.lockRefresh(hash).orElse(null);
        if (refresh == null || refresh.revokedAt() != null) {
            return null;
        }

        Instant now = now();
        if (refresh.consumedAt() != null) {
            store.revokeFamily(user.id(), refresh.family(), now);
            return null;
        }
        if (!refresh.refreshExpiresAt().isAfter(now) || !refresh.familyExpiresAt().isAfter(now)) {
            return null;
        }

        store.consumeRefresh(refresh.id(), now);
        return issueSession(user.id(), refresh.family(), refresh.id(), refresh.passwordAuthenticatedAt(),
                refresh.familyExpiresAt(), now);
    }

    private Session issueSession(
            long userId,
            byte[] family,
            Long predecessor,
            Instant authenticatedAt,
            Instant familyExpiry,
            Instant now
    ) {
        Instant accessExpiry = earlier(now.plus(ACCESS_LIFETIME), familyExpiry);
        Instant refreshExpiry = earlier(now.plus(REFRESH_IDLE), familyExpiry);
        String access = randomToken();
        String refresh = randomToken();
        store.addSession(userId, family, predecessor, sha256(refresh), sha256(access), authenticatedAt,
                accessExpiry, refreshExpiry, familyExpiry, now);
        return new Session(access, refresh,
                Duration.between(now, accessExpiry).toSeconds(), Duration.between(now, refreshExpiry).toSeconds());
    }

    private String reissuePendingVerification(String email, String password) {
        AuthStore.User snapshot = store.findUser(email).filter(AuthStore.User::pending).orElse(null);
        if (snapshot == null || !passwords.matches(password, snapshot.passwordHash())) {
            return null;
        }
        return transactions.execute(status -> store.lockUser(snapshot.id())
                .filter(AuthStore.User::pending)
                .filter(user -> user.passwordHash().equals(snapshot.passwordHash()))
                .map(user -> issueOneUse(VERIFY, user.id(), now(), VERIFY_LIFETIME))
                .orElse(null));
    }

    private String issueOneUse(AuthStore.TokenPurpose purpose, long userId, Instant now, Duration lifetime) {
        String token = randomToken();
        store.consumeOneUseTokens(purpose, userId, now);
        store.addOneUse(purpose, userId, sha256(token), now.plus(lifetime));
        return token;
    }

    private boolean usable(AuthStore.TokenPurpose purpose, byte[] hash, Instant now) {
        return store.lockOneUse(purpose, hash).filter(token -> token.usable(now)).isPresent();
    }

    private String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    private byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return bytes;
    }

    private static Optional<byte[]> presentedHash(String token) {
        if (token == null || token.length() != 43 || !TOKEN_PATTERN.matcher(token).matches()) {
            return Optional.empty();
        }
        return Optional.of(sha256(token));
    }

    private static byte[] sha256(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private String emailLink(String path, String token) {
        return frontendOrigin.resolve(path + "#token=" + token).toASCIIString();
    }

    private static URI frontendOrigin(String text) {
        URI uri = URI.create(text);
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        boolean secureOrigin = "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && loopback);
        if (!secureOrigin || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("studysphere.auth.frontend-origin must be an HTTPS or loopback origin.");
        }
        return uri;
    }

    private static String canonicalEmail(String email) {
        String canonical = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() > 254 || !EMAIL_PATTERN.matcher(canonical).matches()
                || canonical.startsWith(".") || canonical.contains(".@") || canonical.contains("..")) {
            throw denied(400, "INVALID_EMAIL", "Enter a valid email address.");
        }
        return canonical;
    }

    private static void requireNewPassword(String password) {
        if (!acceptableLoginPassword(password) || password.codePointCount(0, password.length()) < 12) {
            throw denied(400, "INVALID_PASSWORD", "Use a password between 12 and 128 characters.");
        }
    }

    private static boolean acceptableLoginPassword(String password) {
        return password != null && !password.isEmpty() && password.length() <= 256
                && password.codePointCount(0, password.length()) <= 128;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static AuthFailure invalidRefresh() {
        return denied(401, "INVALID_REFRESH_TOKEN", "The session is invalid or has expired.");
    }

    private static AuthFailure invalidVerification() {
        return denied(400, "INVALID_VERIFICATION_TOKEN", "The verification link is invalid or has expired.");
    }

    private static AuthFailure invalidReset() {
        return denied(400, "INVALID_RESET_TOKEN", "The password reset link is invalid or has expired.");
    }

    private static AuthFailure denied(int status, String code, String message) {
        return new AuthFailure(status, code, message);
    }

    public record Session(String accessToken, String refreshToken, long expiresInSeconds, long refreshExpiresInSeconds) {
        @Override
        public String toString() {
            return "Session[credentials redacted]";
        }
    }

    public record Identity(long id, String email, List<String> roles, Instant passwordAuthenticatedAt) {
        public Identity {
            roles = List.copyOf(roles);
        }
    }
}
