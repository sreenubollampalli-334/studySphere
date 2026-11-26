package com.studysphere.room.chat;

import com.studysphere.room.RoomService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomChatService {
    private final JdbcTemplate jdbc;
    private final RoomService rooms;
    private final ApplicationEventPublisher events;

    RoomChatService(JdbcTemplate jdbc, RoomService rooms, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.rooms = rooms;
        this.events = events;
    }

    @Transactional
    public Page history(long user, long room, Long after, Long before, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (after != null && before != null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid history page.");
        }
        authorizeLocked(user, room);
        boolean forward = after != null;
        var items = jdbc.query("""
                SELECT m.*, COALESCE(p.display_name, 'Student') AS sender_name
                FROM room_messages m LEFT JOIN student_profiles p ON p.user_id=m.sender_id
                WHERE m.room_id=? AND (? IS NULL OR m.id>?) AND (? IS NULL OR m.id<?)
                """ + (forward ? " ORDER BY m.id ASC" : " ORDER BY m.id DESC") + " LIMIT ? OFFSET ?",
                (row, index) -> new Entry(row.getString("id"), row.getString("sender_id"),
                        row.getString("sender_name"), row.getString("body"),
                        row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                        row.getBoolean("pinned")),
                room, after, after, before, before, size + 1, (long) page * size);
        var slice = items.subList(0, Math.min(items.size(), size));
        return new Page(List.copyOf(forward ? slice : slice.reversed()), page, size, items.size() > size);
    }

    @Transactional
    public Entry send(long user, long room, String body) {
        authorizeLocked(user, room);
        String clean = body == null ? "" : body.strip();
        if (clean.isEmpty() || clean.codePointCount(0, clean.length()) > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a message of 1–2000 characters.");
        }
        var time = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO room_messages (room_id,sender_id,body,created_at) VALUES (?,?,?,?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, room);
            statement.setLong(2, user);
            statement.setString(3, clean);
            statement.setObject(4, LocalDateTime.ofInstant(time, ZoneOffset.UTC));
            return statement;
        }, key);
        long id = key.getKey().longValue();
        String name = jdbc.queryForObject("""
                SELECT COALESCE((SELECT display_name FROM student_profiles WHERE user_id=?), 'Student')
                """, String.class, user);
        events.publishEvent(new Event(room, id, "MESSAGE"));
        return new Entry(Long.toString(id), Long.toString(user), name, clean, time, false);
    }

    @Transactional
    public void pin(long user, long room, long id, boolean pinned) {
        rooms.lockRoom(room);
        rooms.requireModerator(user, room);
        if (Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM room_messages WHERE room_id=? AND id=?)", Boolean.class, room, id))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Room message not found.");
        }
        jdbc.update("UPDATE room_messages SET pinned=? WHERE room_id=? AND id=?", pinned, room, id);
        events.publishEvent(new Event(room, id, "PIN_CHANGED"));
    }

    private void authorizeLocked(long user, long room) {
        rooms.lockRoom(room);
        rooms.requireMember(user, room);
    }

    List<Long> members(long room) {
        return jdbc.queryForList("SELECT user_id FROM room_members WHERE room_id=?", Long.class, room);
    }

    record Event(long room, long id, String type) {}

    public record Entry(String id, String senderId, String senderName, String body, Instant createdAt, boolean pinned) {}
    public record Page(List<Entry> items, int page, int size, boolean hasNext) {}
}
