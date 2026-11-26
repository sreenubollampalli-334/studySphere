package com.studysphere.room;

import com.studysphere.resource.storage.ResourceStorage;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomService {
    private static final String ROOM = """
            SELECT r.*, m.role,
              (SELECT COUNT(*) FROM room_members WHERE room_id=r.id) AS member_count
            FROM study_rooms r LEFT JOIN room_members m ON m.room_id=r.id AND m.user_id=?
            """;
    private static final RowMapper<Room> ROOMS = (rs, i) -> new Room(
            rs.getString("id"), rs.getString("name"), rs.getString("description"), rs.getString("visibility"),
            rs.getInt("capacity"), rs.getInt("member_count"), rs.getString("role"));
    private static final RowMapper<Member> MEMBERS = (rs, i) -> new Member(
            rs.getString("user_id"), rs.getString("display_name"), rs.getString("role"));
    private static final RowMapper<Resource> RESOURCES = (rs, i) -> new Resource(
            rs.getString("id"), rs.getString("title"), rs.getString("file_name"),
            rs.getString("media_type"), rs.getLong("size_bytes"));

    private final JdbcTemplate jdbc;
    private final ResourceStorage storage;

    RoomService(JdbcTemplate jdbc, ResourceStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    public Page<Room> list(long user, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("Choose a valid page.");
        var rows = jdbc.query(ROOM + " WHERE r.visibility='PUBLIC' OR m.role IS NOT NULL ORDER BY r.id DESC LIMIT ? OFFSET ?",
                ROOMS, user, size + 1, (long) page * size);
        return new Page<>(rows.subList(0, Math.min(rows.size(), size)), page, size, rows.size() > size);
    }

    public List<Room> activeRooms(long user) {
        return jdbc.query(ROOM + " WHERE m.role IS NOT NULL ORDER BY r.id DESC LIMIT 10", ROOMS, user);
    }

    public Room get(long user, long room) {
        return first(ROOM + " WHERE r.id=? AND (r.visibility='PUBLIC' OR m.role IS NOT NULL "
                + "OR EXISTS (SELECT 1 FROM room_invitations WHERE room_id=r.id AND user_id=?))", ROOMS, user, room, user);
    }

    @Transactional
    public Room create(long owner, RoomInput input) {
        long room = insert("INSERT INTO study_rooms (owner_id,name,description,visibility,capacity) VALUES (?,?,?,?,?)",
                owner, required(input.name(), 120, "Name"), optional(input.description(), 600, "Description"),
                visibility(input.visibility()), capacity(input.capacity()));
        jdbc.update("INSERT INTO room_members VALUES (?,?,'OWNER')", room, owner);
        return get(owner, room);
    }

    @Transactional
    public Room update(long user, long room, RoomInput input) {
        lockRoom(room);
        requireOwner(user, room);
        Room previous = get(user, room);
        int capacity = input.capacity() == null ? previous.capacity() : capacity(input.capacity());
        if (capacity < previous.memberCount()) throw conflict("Capacity cannot be below current membership.");
        jdbc.update("UPDATE study_rooms SET name=?,description=?,visibility=?,capacity=? WHERE id=?",
                input.name() == null ? previous.name() : required(input.name(), 120, "Name"),
                input.description() == null ? previous.description() : optional(input.description(), 600, "Description"),
                input.visibility() == null ? previous.visibility() : visibility(input.visibility()), capacity, room);
        return get(user, room);
    }

    @Transactional
    public void delete(long user, long room) {
        lockRoom(room);
        requireOwner(user, room);
        jdbc.update("DELETE FROM study_rooms WHERE id=?", room);
    }

    public void lockRoom(long room) {
        first("SELECT id FROM study_rooms WHERE id=? FOR UPDATE", (rs, i) -> rs.getLong(1), room);
    }

    public void requireMember(long user, long room) {
        role(user, room);
    }

    public void requireModerator(long user, long room) {
        if (role(user, room).equals("MEMBER")) throw forbidden();
    }

    private void requireOwner(long user, long room) {
        if (!role(user, room).equals("OWNER")) throw forbidden();
    }

    private String role(long user, long room) {
        return first("SELECT role FROM room_members WHERE room_id=? AND user_id=?", (rs, i) -> rs.getString(1), room, user);
    }

    public List<Member> members(long user, long room) {
        requireMember(user, room);
        return jdbc.query("""
                SELECT m.user_id,m.role,p.display_name FROM room_members m
                LEFT JOIN student_profiles p ON p.user_id=m.user_id
                WHERE m.room_id=? ORDER BY m.user_id
                """, MEMBERS, room);
    }

    @Transactional
    public Room join(long user, long room) {
        lockRoom(room);
        Room current = get(user, room);
        if (current.role() != null) return current;
        if (current.memberCount() >= current.capacity()) throw conflict("The room is full.");
        jdbc.update("INSERT INTO room_members VALUES (?,?,'MEMBER')", room, user);
        jdbc.update("DELETE FROM room_invitations WHERE room_id=? AND user_id=?", room, user);
        return get(user, room);
    }

    @Transactional
    public void leave(long user, long room) {
        lockRoom(room);
        if (role(user, room).equals("OWNER")) throw conflict("The owner must delete the room to leave.");
        jdbc.update("DELETE FROM room_members WHERE room_id=? AND user_id=?", room, user);
    }

    @Transactional
    public void changeRole(long user, long room, long target, String desired) {
        lockRoom(room);
        requireOwner(user, room);
        if (desired == null || !Set.of("MODERATOR", "MEMBER").contains(desired)) throw invalid("Choose a valid role.");
        if (role(target, room).equals("OWNER")) throw forbidden();
        jdbc.update("UPDATE room_members SET role=? WHERE room_id=? AND user_id=?", desired, room, target);
    }

    @Transactional
    public void removeMember(long user, long room, long target) {
        lockRoom(room);
        requireModerator(user, room);
        String targetRole = role(target, room);
        if (targetRole.equals("OWNER") || (targetRole.equals("MODERATOR") && !role(user, room).equals("OWNER"))) {
            throw forbidden();
        }
        jdbc.update("DELETE FROM room_members WHERE room_id=? AND user_id=?", room, target);
    }

    public List<Room> incomingInvitations(long user) {
        return jdbc.query(ROOM + " JOIN room_invitations i ON i.room_id=r.id AND i.user_id=? ORDER BY r.id DESC",
                ROOMS, user, user);
    }

    public List<Invitation> invitations(long user, long room) {
        requireModerator(user, room);
        return jdbc.query("""
                SELECT i.user_id,p.display_name FROM room_invitations i
                LEFT JOIN student_profiles p ON p.user_id=i.user_id WHERE i.room_id=? ORDER BY i.user_id
                """, (rs, i) -> new Invitation(rs.getString(1), rs.getString(2)), room);
    }

    @Transactional
    public Invitation invite(long user, long room, String email) {
        lockRoom(room);
        requireModerator(user, room);
        String canonicalEmail = required(email, 254, "Email").toLowerCase(Locale.ROOT);
        Invitation target = first("""
                SELECT u.id,p.display_name FROM users u LEFT JOIN student_profiles p ON p.user_id=u.id
                WHERE u.canonical_email=? AND u.status='ACTIVE'
                """, (rs, i) -> new Invitation(rs.getString(1), rs.getString(2)), canonicalEmail);
        long recipient = id(target.userId());
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM room_members WHERE room_id=? AND user_id=?)", Boolean.class, room, recipient))) {
            throw conflict("This person already belongs to the room.");
        }
        jdbc.update("INSERT INTO room_invitations VALUES (?,?) ON DUPLICATE KEY UPDATE user_id=room_invitations.user_id",
                room, recipient);
        return target;
    }

    @Transactional
    public void revokeInvitation(long user, long room, long target) {
        lockRoom(room);
        requireModerator(user, room);
        jdbc.update("DELETE FROM room_invitations WHERE room_id=? AND user_id=?", room, target);
    }

    public List<AgendaItem> agenda(long user, long room) {
        requireMember(user, room);
        return jdbc.query("SELECT id,text FROM room_agenda_items WHERE room_id=? ORDER BY id",
                (rs, i) -> new AgendaItem(rs.getString(1), rs.getString(2)), room);
    }

    @Transactional
    public AgendaItem addAgenda(long user, long room, String text) {
        lockRoom(room);
        requireModerator(user, room);
        String cleaned = required(text, 1000, "Agenda item");
        long id = insert("INSERT INTO room_agenda_items (room_id,text) VALUES (?,?)", room, cleaned);
        return new AgendaItem(Long.toString(id), cleaned);
    }

    @Transactional
    public void removeAgenda(long user, long room, long id) {
        lockRoom(room);
        requireModerator(user, room);
        if (jdbc.update("DELETE FROM room_agenda_items WHERE room_id=? AND id=?", room, id) == 0) throw missing();
    }

    public List<Resource> resources(long user, long room) {
        requireMember(user, room);
        return jdbc.query("""
                SELECT r.* FROM resources r JOIN room_resources rr ON rr.resource_id=r.id
                WHERE rr.room_id=? ORDER BY r.id DESC
                """, RESOURCES, room);
    }

    @Transactional
    public Resource shareResource(long user, long room, long resourceId) {
        lockRoom(room);
        requireMember(user, room);
        Resource resource = first("SELECT * FROM resources WHERE id=? AND user_id=? FOR SHARE",
                RESOURCES, resourceId, user);
        jdbc.update("INSERT INTO room_resources VALUES (?,?,?) ON DUPLICATE KEY UPDATE shared_by=room_resources.shared_by",
                room, resourceId, user);
        return resource;
    }

    @Transactional
    public void unshareResource(long user, long room, long resource) {
        lockRoom(room);
        String role = role(user, room);
        long sharer = first("SELECT shared_by FROM room_resources WHERE room_id=? AND resource_id=?",
                (rs, i) -> rs.getLong(1), room, resource);
        if (sharer != user && role.equals("MEMBER")) throw forbidden();
        jdbc.update("DELETE FROM room_resources WHERE room_id=? AND resource_id=?", room, resource);
    }

    @Transactional
    public Download download(long user, long room, long resourceId) {
        lockRoom(room);
        requireMember(user, room);
        return first("""
                SELECT r.* FROM resources r JOIN room_resources rr ON rr.resource_id=r.id
                WHERE rr.room_id=? AND r.id=? FOR SHARE
                """, (rs, i) -> new Download(RESOURCES.mapRow(rs, i), storage.open(rs.getString("object_key"))),
                room, resourceId);
    }

    private <T> T first(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst().orElseThrow(RoomService::missing);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    public static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Invalid identifiers produce the same response.
        }
        throw invalid("Choose a valid identifier.");
    }

    private static String required(String value, int max, String label) {
        String cleaned = optional(value, max, label);
        if (cleaned.isEmpty()) throw invalid(label + " is required.");
        return cleaned;
    }

    private static String optional(String value, int max, String label) {
        String cleaned = value == null ? "" : value.strip();
        if (cleaned.length() > max) throw invalid(label + " is too long.");
        return cleaned;
    }

    private static String visibility(String value) {
        if (value == null || !Set.of("PUBLIC", "PRIVATE").contains(value)) throw invalid("Choose a valid visibility.");
        return value;
    }

    private static int capacity(Integer value) {
        if (value == null || value < 2 || value > 100) throw invalid("Choose a capacity from 2 to 100.");
        return value;
    }

    private static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Room item not found."); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "Room permission required."); }

    public record Room(String id, String name, String description, String visibility, int capacity, int memberCount, String role) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    record RoomInput(String name, String description, String visibility, Integer capacity) {}
    record Member(String userId, String displayName, String role) {}
    record Invitation(String userId, String displayName) {}
    record AgendaItem(String id, String text) {}
    record Resource(String id, String title, String fileName, String mediaType, long sizeBytes) {}
    record Download(Resource resource, InputStream content) {}
}
