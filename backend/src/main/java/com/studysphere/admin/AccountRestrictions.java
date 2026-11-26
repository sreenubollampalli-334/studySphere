package com.studysphere.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccountRestrictions {
    private final JdbcTemplate jdbc;

    public AccountRestrictions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean active(long user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM admin_account_restrictions
                WHERE user_id=? AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(6)))
                """, Boolean.class, user));
    }
}
