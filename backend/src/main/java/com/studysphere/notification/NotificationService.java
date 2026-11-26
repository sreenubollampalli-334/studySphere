package com.studysphere.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private static final Preferences DEFAULTS = new Preferences(true, true, true, true, true);
    private final JdbcTemplate jdbc;

    public NotificationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Preferences preferences(long userId) {
        return jdbc.query("""
                SELECT task_reminders,invitations,messages,exams,revision
                FROM notification_preferences WHERE user_id=?
                """, (row, index) -> new Preferences(row.getBoolean(1), row.getBoolean(2),
                row.getBoolean(3), row.getBoolean(4), row.getBoolean(5)), userId)
                .stream().findFirst().orElse(DEFAULTS);
    }

    public Preferences save(long userId, Preferences settings) {
        if (settings.taskReminders() == null || settings.invitations() == null || settings.messages() == null
                || settings.exams() == null || settings.revision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supply every notification preference.");
        }
        jdbc.update("""
                INSERT INTO notification_preferences(user_id,task_reminders,invitations,messages,exams,revision)
                VALUES (?,?,?,?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE task_reminders=incoming.task_reminders,invitations=incoming.invitations,
                    messages=incoming.messages,exams=incoming.exams,revision=incoming.revision
                """, userId, settings.taskReminders(), settings.invitations(), settings.messages(),
                settings.exams(), settings.revision());
        return settings;
    }

    public Page list(long userId, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification page.");
        }
        var items = jdbc.query("""
                SELECT id,type,title,body,created_at,read_at FROM notifications
                WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (row, index) -> new Item(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), utc(row.getObject(5, LocalDateTime.class)), utc(row.getObject(6, LocalDateTime.class))),
                userId, size + 1, (long) page * size);
        long unread = jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id=? AND read_at IS NULL",
                Long.class, userId);
        return new Page(items.stream().limit(size).toList(), page, size, items.size() > size, unread);
    }

    public void read(long userId, long id) {
        if (jdbc.update("""
                UPDATE notifications SET read_at=COALESCE(read_at,UTC_TIMESTAMP(6)) WHERE id=? AND user_id=?
                """, id, userId) == 0 && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM notifications WHERE id=? AND user_id=?)", Boolean.class, id, userId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found.");
        }
    }

    @Scheduled(fixedDelayString = "${studysphere.notifications.poll-ms:15000}")
    @Transactional
    public void deliver() {
        tasks();
        invitations();
        messages();
        examReminders();
        revisionReminders();
    }

    private void tasks() {
        // A lock on each source row makes its consumption atomic across schedulers and retries.
        var due = jdbc.query("""
                SELECT r.id,t.user_id,t.title,t.state FROM task_reminders r
                JOIN study_tasks t ON t.id=r.task_id
                WHERE r.state='PENDING' AND r.scheduled_at<=UTC_TIMESTAMP(6)
                ORDER BY r.scheduled_at,r.id LIMIT 100 FOR UPDATE SKIP LOCKED
                """, (row, index) -> new TaskReminder(row.getLong(1), row.getLong(2), row.getString(3),
                row.getString(4).equals("OPEN")));
        for (TaskReminder item : due) {
            boolean enabled = item.open() && preferences(item.userId()).taskReminders();
            if (enabled) insert(item.userId(), "task:" + item.id(), "TASK_REMINDER", "Study task reminder", item.title());
            jdbc.update("UPDATE task_reminders SET state=? WHERE id=?", enabled ? "DELIVERED" : "SKIPPED", item.id());
        }
    }

    private void invitations() {
        jdbc.update("""
                INSERT INTO notifications(user_id,event_key,type,title,body,created_at)
                SELECT i.user_id,CONCAT('room:',i.room_id),'INVITATION','Study room invitation',
                    r.name,UTC_TIMESTAMP(6)
                FROM room_invitations i JOIN study_rooms r ON r.id=i.room_id
                LEFT JOIN notifications n ON n.user_id=i.user_id AND n.event_key=CONCAT('room:',i.room_id)
                LEFT JOIN notification_preferences p ON p.user_id=i.user_id
                WHERE n.id IS NULL AND COALESCE(p.invitations,TRUE)
                ORDER BY i.room_id,i.user_id LIMIT 100
                ON DUPLICATE KEY UPDATE read_at=notifications.read_at
                """);
        jdbc.update("""
                INSERT INTO notifications(user_id,event_key,type,title,body,created_at)
                SELECT target.user_id,CONCAT('connection:',target.id),'INVITATION','Connection invitation',
                    'Review your pending connection requests.',UTC_TIMESTAMP(6)
                FROM (SELECT id,IF(requester_id=low_user_id,high_user_id,low_user_id) AS user_id
                      FROM connection_requests WHERE status='PENDING') target
                LEFT JOIN notification_preferences p ON p.user_id=target.user_id
                LEFT JOIN notifications n ON n.user_id=target.user_id AND n.event_key=CONCAT('connection:',target.id)
                WHERE n.id IS NULL AND COALESCE(p.invitations,TRUE)
                ORDER BY target.id LIMIT 100
                ON DUPLICATE KEY UPDATE read_at=notifications.read_at
                """);
    }

    private void messages() {
        // Keep private message contents out of notification history.
        jdbc.update("""
                INSERT INTO notifications(user_id,event_key,type,title,body,created_at)
                SELECT recent.recipient,CONCAT('message:',recent.id),'MESSAGE','New private message',
                    'Open private messages to continue the conversation.',UTC_TIMESTAMP(6)
                FROM (
                    SELECT m.id,m.conversation_id,m.sender_id,c.member_low,c.member_high,
                        IF(m.sender_id=c.member_low,c.member_high,c.member_low) AS recipient
                    FROM private_messages m JOIN conversations c ON c.id=m.conversation_id
                    WHERE NOT m.deleted AND m.created_at>=UTC_TIMESTAMP(6)-INTERVAL 1 DAY
                ) recent
                JOIN connection_requests cr ON cr.low_user_id=recent.member_low AND cr.high_user_id=recent.member_high
                    AND cr.status='ACCEPTED'
                LEFT JOIN notifications n ON n.user_id=recent.recipient AND n.event_key=CONCAT('message:',recent.id)
                LEFT JOIN notification_preferences p ON p.user_id=recent.recipient
                LEFT JOIN private_message_receipts receipt
                    ON receipt.conversation_id=recent.conversation_id AND receipt.user_id=recent.recipient
                WHERE n.id IS NULL AND COALESCE(p.messages,TRUE)
                    AND recent.id>COALESCE(receipt.last_read_id,0)
                    AND NOT EXISTS (
                        SELECT 1 FROM user_blocks b
                        WHERE (b.blocker_id=recent.recipient AND b.blocked_id=recent.sender_id)
                           OR (b.blocker_id=recent.sender_id AND b.blocked_id=recent.recipient)
                    )
                ORDER BY recent.id LIMIT 100
                ON DUPLICATE KEY UPDATE read_at=notifications.read_at
                """);
    }

    private void examReminders() {
        var candidates = jdbc.query("""
                SELECT e.id,e.user_id,e.exam_date,e.time_zone,e.title FROM exams e
                LEFT JOIN notification_preferences p ON p.user_id=e.user_id
                LEFT JOIN notifications n ON n.user_id=e.user_id AND n.event_key=CONCAT('exam:',e.id,':',e.exam_date)
                WHERE n.id IS NULL AND COALESCE(p.exams,TRUE)
                    AND e.exam_date BETWEEN UTC_DATE()-INTERVAL 1 DAY AND UTC_DATE()+INTERVAL 2 DAY
                ORDER BY e.exam_date,e.id
                """, (row, index) -> new ExamReminder(row.getLong(1), row.getLong(2),
                row.getObject(3, LocalDate.class), row.getString(4), row.getString(5)));
        for (ExamReminder exam : candidates) {
            LocalDate today = LocalDate.now(ZoneId.of(exam.zone()));
            if (exam.date().equals(today) || exam.date().equals(today.plusDays(1))) {
                insert(exam.userId(), "exam:" + exam.id() + ":" + exam.date(), "EXAM", "Upcoming exam",
                        exam.title() + " · " + exam.date());
            }
        }
    }

    private void revisionReminders() {
        jdbc.update("""
                INSERT INTO notifications(user_id,event_key,type,title,body,created_at)
                SELECT d.user_id,CONCAT('revision:',d.id,':',UTC_DATE()),'REVISION','Flashcards due',
                    'You have flashcards ready for review.',UTC_TIMESTAMP(6)
                FROM flashcard_decks d
                LEFT JOIN notification_preferences p ON p.user_id=d.user_id
                LEFT JOIN notifications n ON n.user_id=d.user_id AND n.event_key=CONCAT('revision:',d.id,':',UTC_DATE())
                WHERE n.id IS NULL AND COALESCE(p.revision,TRUE)
                    AND EXISTS(SELECT 1 FROM flashcards c WHERE c.deck_id=d.id AND c.due_at<=UTC_TIMESTAMP(6))
                    AND (d.document_id IS NULL OR EXISTS(
                        SELECT 1 FROM documents doc JOIN resources r ON r.id=doc.resource_id
                        WHERE doc.id=d.document_id AND doc.status='READY'
                            AND (r.user_id=d.user_id OR EXISTS (
                                SELECT 1 FROM resource_permissions grant_row
                                WHERE grant_row.resource_id=r.id AND grant_row.user_id=d.user_id
                            ))
                    ))
                ORDER BY d.id LIMIT 100
                ON DUPLICATE KEY UPDATE read_at=notifications.read_at
                """);
    }

    private void insert(long userId, String key, String type, String title, String body) {
        jdbc.update("""
                INSERT INTO notifications(user_id,event_key,type,title,body,created_at)
                VALUES(?,?,?,?,?,UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE read_at=notifications.read_at
                """, userId, key, type, title, body);
    }

    private static Instant utc(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private record TaskReminder(long id, long userId, String title, boolean open) {}
    private record ExamReminder(long id, long userId, LocalDate date, String zone, String title) {}
    public record Preferences(Boolean taskReminders, Boolean invitations, Boolean messages, Boolean exams, Boolean revision) {}
    public record Item(String id, String type, String title, String body, Instant createdAt, Instant readAt) {}
    public record Page(List<Item> items, int page, int size, boolean hasNext, long unreadCount) {}
}
