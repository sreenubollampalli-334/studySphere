package com.studysphere.admin;

import com.studysphere.admin.ReportIntake.Kind;
import com.studysphere.community.CommunityService;
import com.studysphere.messaging.MessageService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminService {
    enum ReportStatus { OPEN, RESOLVED, DISMISSED }

    private final JdbcTemplate jdbc;
    private final MessageService messages;
    private final CommunityService communities;
    private final BigDecimal inputRate;
    private final BigDecimal outputRate;

    AdminService(JdbcTemplate jdbc, MessageService messages, CommunityService communities,
                 @Value("${studysphere.admin.cost.input-per-million-characters:-1}") BigDecimal inputRate,
                 @Value("${studysphere.admin.cost.output-per-million-characters:-1}") BigDecimal outputRate) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.communities = communities;
        this.inputRate = inputRate;
        this.outputRate = outputRate;
    }

    @Transactional(readOnly = true)
    public Page<Report> reports(ReportStatus status, int page, int size) {
        long offset = offset(page, size);
        String filter = status == null ? "" : status.name();
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM admin_reports WHERE (?='' OR status=?)",
                Long.class, filter, filter);
        var items = jdbc.query("""
                SELECT r.kind,r.target_id,r.reporter_id,r.subject_id,r.status,r.created_at,r.updated_at,r.reason,
                       CASE WHEN p.id IS NOT NULL THEN LEFT(CONCAT(p.title,': ',p.body),500)
                            WHEN c.id IS NOT NULL THEN LEFT(c.body,500) END AS excerpt
                FROM admin_reports r
                LEFT JOIN community_posts p ON r.kind='COMMUNITY_POST' AND p.id=r.target_id
                LEFT JOIN community_comments c ON r.kind='COMMUNITY_COMMENT' AND c.id=r.target_id
                WHERE (?='' OR r.status=?)
                ORDER BY r.created_at DESC,r.kind,r.target_id DESC,r.reporter_id DESC LIMIT ? OFFSET ?
                """, (r,i) -> new Report(Kind.valueOf(r.getString("kind")), r.getString("target_id"),
                r.getString("reporter_id"), r.getString("subject_id"), ReportStatus.valueOf(r.getString("status")),
                instant(r,"created_at"), instant(r,"updated_at"), r.getString("reason"), r.getString("excerpt")),
                filter, filter, size, offset);
        return new Page<>(items,total,page,size);
    }

    @Transactional
    public void review(long actor, Kind kind, long target, long reporter, ReportStatus status, String reason, boolean hide) {
        if (status == null || status == ReportStatus.OPEN || (hide && status != ReportStatus.RESOLVED)) {
            throw bad("Choose a final report status. Only resolved reports may remove content.");
        }
        String validatedReason = ReportIntake.requiredReason(reason);
        var state = jdbc.queryForList("""
                SELECT status FROM admin_reports WHERE kind=? AND target_id=? AND reporter_id=?
                """, String.class, kind.name(), target, reporter);
        if (state.isEmpty()) throw missing("Report");
        if (!"OPEN".equals(state.getFirst())) throw conflict("This report was already reviewed.");
        if (hide) {
            if (kind == Kind.PRIVATE_MESSAGE) messages.hideReportedMessage(target);
            else communities.hideReportedContent(kind, target);
        }
        int changed = jdbc.update("""
                UPDATE admin_reports SET status=?,updated_at=UTC_TIMESTAMP(6)
                WHERE kind=? AND target_id=? AND reporter_id=? AND status='OPEN'
                """, status.name(), kind.name(), target, reporter);
        if (changed != 1) throw conflict("This report was already reviewed.");
        String action = hide ? "REPORT_RESOLVED_AND_HIDDEN" : "REPORT_" + status.name();
        audit(actor, action, kind.name() + "/" + target + "/" + reporter, validatedReason);
    }

    @Transactional(readOnly = true)
    public Page<Restriction> restrictions(int page, int size) {
        long offset = offset(page, size);
        String active = " WHERE expires_at IS NULL OR expires_at>UTC_TIMESTAMP(6)";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM admin_account_restrictions" + active, Long.class);
        var items = jdbc.query("""
                SELECT user_id,reason,created_at,expires_at FROM admin_account_restrictions
                """ + active + " ORDER BY created_at DESC,user_id DESC LIMIT ? OFFSET ?",
                (r,i) -> new Restriction(r.getString(1),r.getString(2),instant(r,"created_at"),instant(r,"expires_at")),
                size, offset);
        return new Page<>(items,total,page,size);
    }

    @Transactional
    public void restrict(long actor, long user, String reason, Instant expiresAt) {
        String validatedReason = ReportIntake.requiredReason(reason);
        lockUser(user);
        if (actor == user || !jdbc.queryForList("""
                SELECT 1 FROM user_roles ur JOIN roles r ON ur.role_id=r.id WHERE ur.user_id=? AND r.code='ADMIN'
                """, Integer.class, user).isEmpty()) throw conflict("Administrator accounts cannot be restricted.");
        LocalDateTime expiry = null;
        if (expiresAt != null) {
            var now = Instant.now();
            if (!expiresAt.isAfter(now.plusSeconds(1)) || expiresAt.isAfter(now.plusSeconds(315576000))) {
                throw bad("Choose an expiry in the future, within ten years.");
            }
            expiry = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC);
        }
        jdbc.update("""
                INSERT INTO admin_account_restrictions(user_id,reason,created_at,expires_at)
                VALUES (?,?,UTC_TIMESTAMP(6),?) AS entry
                ON DUPLICATE KEY UPDATE reason=entry.reason,created_at=entry.created_at,expires_at=entry.expires_at
                """, user, validatedReason, expiry);
        jdbc.update("UPDATE refresh_tokens SET revoked_at=UTC_TIMESTAMP(6) WHERE user_id=? AND revoked_at IS NULL", user);
        audit(actor,"ACCOUNT_RESTRICTED",Long.toString(user),validatedReason);
    }

    @Transactional
    public void lift(long actor, long user, String reason) {
        String validatedReason = ReportIntake.requiredReason(reason);
        lockUser(user);
        if (jdbc.update("DELETE FROM admin_account_restrictions WHERE user_id=?", user) == 0) {
            throw missing("Restriction");
        }
        audit(actor,"ACCOUNT_RESTRICTION_LIFTED",Long.toString(user),validatedReason);
    }

    @Transactional(readOnly = true)
    public Page<Audit> audits(int page, int size) {
        long offset = offset(page,size);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audits", Long.class);
        var items = jdbc.query("""
                SELECT id,actor_id,action,target,reason,created_at FROM admin_audits
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (r,i) -> new Audit(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                instant(r,"created_at")), size, offset);
        return new Page<>(items,total,page,size);
    }

    public Usage usage(int days) {
        if (days < 1 || days > 90) throw bad("Choose a period from 1 to 90 days.");
        var items = jdbc.query("""
                SELECT provider,COUNT(*) AS requests,SUM(status='COMPLETE') AS completed,
                       SUM(input_characters) AS input_count,SUM(output_characters) AS output_count
                FROM ai_usage_logs WHERE started_at>=TIMESTAMPADD(DAY,-?,UTC_TIMESTAMP(6))
                GROUP BY provider ORDER BY provider
                """, (r,i) -> new UsageItem(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),
                estimatedCost(r.getLong(4),r.getLong(5))), days);
        return new Usage(days,items);
    }

    private BigDecimal estimatedCost(long input, long output) {
        if (inputRate.signum() < 0 || outputRate.signum() < 0) return null;
        return inputRate.multiply(BigDecimal.valueOf(input)).add(outputRate.multiply(BigDecimal.valueOf(output)))
                .movePointLeft(6).setScale(6,RoundingMode.HALF_UP);
    }

    private void lockUser(long user) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, user).isEmpty()) {
            throw missing("Account");
        }
    }

    private void audit(long actor, String action, String target, String reason) {
        jdbc.update("""
                INSERT INTO admin_audits(actor_id,action,target,reason,created_at) VALUES (?,?,?,?,UTC_TIMESTAMP(6))
                """, actor, action, target, reason);
    }

    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw bad("Choose a valid page and a page size from 1 to 100.");
        return (long) page * size;
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column,LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static ResponseStatusException missing(String entity) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,entity + " not found.");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }

    public record Page<T>(List<T> items, long total, int page, int size) {}
    public record Report(Kind kind, String targetId, String reporterId, String subjectId, ReportStatus status,
                         Instant createdAt, Instant updatedAt, String reason, String content) {}
    public record Restriction(String userId, String reason, Instant createdAt, Instant expiresAt) {}
    public record Audit(String id, String actorId, String action, String target, String reason, Instant createdAt) {}
    public record Usage(int days, List<UsageItem> items) {}
    public record UsageItem(String provider, long requests, long completed, long inputCharacters,
                            long outputCharacters, BigDecimal estimatedCostUsd) {}
}
