package com.studysphere.admin;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportIntake {
    public enum Kind { PRIVATE_MESSAGE, COMMUNITY_POST, COMMUNITY_COMMENT }

    private final JdbcTemplate jdbc;

    public ReportIntake(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Kind kind, long target, long reporter, long subject, String reason) {
        String validatedReason = requiredReason(reason);
        jdbc.update("""
                INSERT INTO admin_reports(kind,target_id,reporter_id,subject_id,reason,created_at,updated_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE target_id=admin_reports.target_id
                """, kind.name(), target, reporter, subject, kind == Kind.PRIVATE_MESSAGE ? null : validatedReason);
    }

    static String requiredReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a reason up to 500 characters.");
        }
        return reason.strip();
    }
}
