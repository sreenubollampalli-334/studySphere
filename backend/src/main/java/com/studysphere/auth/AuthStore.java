package com.studysphere.auth;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
class AuthStore {

    private static final String USER_COLUMNS = "id, canonical_email, password_hash, status, verified_at";
    private static final RowMapper<User> USER = (result, row) -> new User(
            result.getLong("id"),
            result.getString("canonical_email"),
            result.getString("password_hash"),
            result.getString("status"),
            instant(result, "verified_at")
    );
    private static final RowMapper<Refresh> REFRESH = (result, row) -> new Refresh(
            result.getLong("id"),
            result.getBytes("family_id"),
            instant(result, "password_authenticated_at"),
            instant(result, "refresh_expires_at"),
            instant(result, "family_expires_at"),
            instant(result, "consumed_at"),
            instant(result, "revoked_at")
    );
    private static final RowMapper<OneUse> ONE_USE = (result, row) -> new OneUse(
            instant(result, "expires_at"),
            instant(result, "consumed_at")
    );

    private final JdbcTemplate jdbc;

    AuthStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<User> findUser(String email) {
        return first("SELECT " + USER_COLUMNS + " FROM users WHERE canonical_email = ?", USER, email);
    }

    Optional<User> lockUser(long id) {
        return first("SELECT " + USER_COLUMNS + " FROM users WHERE id = ? FOR UPDATE", USER, id);
    }

    long createStudent(String email, String passwordHash, Instant now) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users (canonical_email, password_hash, status, created_at, updated_at)
                    VALUES (?, ?, 'PENDING_VERIFICATION', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.setObject(3, utc(now));
            statement.setObject(4, utc(now));
            return statement;
        }, key);
        Number userId = key.getKey();
        if (userId == null) {
            throw new IllegalStateException("The new account has no generated ID.");
        }

        if (jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT ?, id FROM roles WHERE code = 'STUDENT'
                """, userId.longValue()) != 1) {
            throw new IllegalStateException("The STUDENT role is missing.");
        }
        return userId.longValue();
    }

    void activateUser(long userId, Instant now) {
        jdbc.update("""
                UPDATE users SET status = 'ACTIVE', verified_at = ?, updated_at = ? WHERE id = ?
                """, utc(now), utc(now), userId);
    }

    void changePassword(long userId, String passwordHash, Instant now) {
        jdbc.update("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                passwordHash, utc(now), userId);
    }

    void deleteUser(long userId) {
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    List<String> roles(long userId) {
        return jdbc.query("""
                SELECT roles.code
                FROM roles JOIN user_roles ON user_roles.role_id = roles.id
                WHERE user_roles.user_id = ?
                ORDER BY roles.code
                """, (result, row) -> result.getString(1), userId);
    }

    Optional<Long> refreshOwner(byte[] hash) {
        return first("SELECT user_id FROM refresh_tokens WHERE refresh_hash = ?",
                (result, row) -> result.getLong(1), hash);
    }

    Optional<Refresh> lockRefresh(byte[] hash) {
        return first("""
                SELECT id, family_id, password_authenticated_at, refresh_expires_at, family_expires_at,
                       consumed_at, revoked_at
                FROM refresh_tokens WHERE refresh_hash = ? FOR UPDATE
                """, REFRESH, hash);
    }

    void addSession(
            long userId,
            byte[] family,
            Long predecessor,
            byte[] refreshHash,
            byte[] accessHash,
            Instant passwordAuthenticatedAt,
            Instant accessExpiry,
            Instant refreshExpiry,
            Instant familyExpiry,
            Instant now
    ) {
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, family_id, predecessor_id, refresh_hash, access_hash,
                    password_authenticated_at, access_expires_at, refresh_expires_at, family_expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, userId, family, predecessor, refreshHash, accessHash, utc(passwordAuthenticatedAt),
                utc(accessExpiry), utc(refreshExpiry), utc(familyExpiry), utc(now));
    }

    void consumeRefresh(long id, Instant now) {
        jdbc.update("UPDATE refresh_tokens SET consumed_at = ? WHERE id = ?", utc(now), id);
    }

    void revokeFamily(long userId, byte[] family, Instant now) {
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = ?
                WHERE user_id = ? AND family_id = ? AND revoked_at IS NULL
                """, utc(now), userId, family);
    }

    void revokeAllSessions(long userId, Instant now) {
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL
                """, utc(now), userId);
    }

    Optional<BearerUser> findBearerUser(byte[] accessHash, Instant now) {
        return first("""
                SELECT users.id, users.canonical_email, refresh_tokens.password_authenticated_at
                FROM refresh_tokens JOIN users ON users.id = refresh_tokens.user_id
                WHERE refresh_tokens.access_hash = ? AND refresh_tokens.access_expires_at > ?
                    AND refresh_tokens.family_expires_at > ? AND refresh_tokens.consumed_at IS NULL
                    AND refresh_tokens.revoked_at IS NULL AND users.status = 'ACTIVE'
                    AND users.verified_at IS NOT NULL
                """, (result, row) -> new BearerUser(
                result.getLong("id"),
                result.getString("canonical_email"),
                instant(result, "password_authenticated_at")
        ), accessHash, utc(now), utc(now));
    }

    Optional<Long> oneUseOwner(TokenPurpose purpose, byte[] hash) {
        return first("SELECT user_id FROM " + purpose.table + " WHERE token_hash = ?",
                (result, row) -> result.getLong(1), hash);
    }

    Optional<OneUse> lockOneUse(TokenPurpose purpose, byte[] hash) {
        return first("SELECT expires_at, consumed_at FROM " + purpose.table + " WHERE token_hash = ? FOR UPDATE",
                ONE_USE, hash);
    }

    void consumeOneUseTokens(TokenPurpose purpose, long userId, Instant now) {
        jdbc.update("UPDATE " + purpose.table + " SET consumed_at = ? WHERE user_id = ? AND consumed_at IS NULL",
                utc(now), userId);
    }

    void addOneUse(TokenPurpose purpose, long userId, byte[] hash, Instant expiresAt) {
        jdbc.update("INSERT INTO " + purpose.table + " (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
                userId, hash, utc(expiresAt));
    }

    private <T> Optional<T> first(String sql, RowMapper<T> mapper, Object... parameters) {
        return jdbc.query(sql, mapper, parameters).stream().findFirst();
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        LocalDateTime datetime = result.getObject(column, LocalDateTime.class);
        return datetime == null ? null : datetime.toInstant(ZoneOffset.UTC);
    }

    enum TokenPurpose {
        VERIFY("verification_tokens"),
        RESET("password_reset_tokens");

        private final String table;

        TokenPurpose(String table) {
            this.table = table;
        }
    }

    record User(long id, String email, String passwordHash, String status, Instant verifiedAt) {
        boolean active() {
            return "ACTIVE".equals(status) && verifiedAt != null;
        }

        boolean pending() {
            return "PENDING_VERIFICATION".equals(status);
        }
    }

    record Refresh(
            long id,
            byte[] family,
            Instant passwordAuthenticatedAt,
            Instant refreshExpiresAt,
            Instant familyExpiresAt,
            Instant consumedAt,
            Instant revokedAt
    ) {}

    record OneUse(Instant expiresAt, Instant consumedAt) {
        boolean usable(Instant now) {
            return consumedAt == null && expiresAt.isAfter(now);
        }
    }

    record BearerUser(long id, String email, Instant passwordAuthenticatedAt) {}
}
