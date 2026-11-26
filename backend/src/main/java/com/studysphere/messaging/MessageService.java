package com.studysphere.messaging;

import com.studysphere.admin.ReportIntake;
import com.studysphere.auth.AccountDeletionRequested;
import com.studysphere.matching.ConnectionService;
import com.studysphere.resource.storage.ResourceStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MessageService {
    private final JdbcTemplate jdbc;
    private final ConnectionService connections;
    private final ResourceStorage storage;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final ReportIntake reports;

    MessageService(JdbcTemplate jdbc, ConnectionService connections, ResourceStorage storage,
                   ApplicationEventPublisher events, PlatformTransactionManager manager, ReportIntake reports) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.storage = storage;
        this.events = events;
        this.reports = reports;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Conversation start(long owner, long peer) {
        if (owner == peer) throw invalid("Choose a connected partner.");
        connections.lockPair(owner, peer);
        if (!connections.canMessage(owner, peer)) throw missing();
        long low = Math.min(owner, peer), high = Math.max(owner, peer);
        jdbc.update("INSERT IGNORE INTO conversations(member_low,member_high) VALUES (?,?)", low, high);
        Long id = jdbc.queryForObject("SELECT id FROM conversations WHERE member_low=? AND member_high=?", Long.class, low, high);
        return summary(owner, id, peer);
    }

    public Page<Conversation> list(long owner, int page, int size) {
        int offset = offset(page, size);
        var result = jdbc.query("""
                SELECT id, IF(member_low=?,member_high,member_low) peer
                FROM conversations WHERE member_low=? OR member_high=? ORDER BY id DESC
                """, (r, n) -> new Pair(r.getLong("id"), r.getLong("peer")), owner, owner, owner)
                .stream().filter(pair -> connections.canMessage(owner, pair.peer())).skip(offset).limit(size + 1L)
                .map(pair -> summary(owner, pair.id(), pair.peer())).toList();
        return page(result, page, size);
    }

    private Conversation summary(long owner, long id, long peer) {
        String name = jdbc.queryForObject("""
                SELECT COALESCE((SELECT display_name FROM student_profiles WHERE user_id=?),'Study partner')
                """, String.class, peer);
        Long unread = jdbc.queryForObject("""
                SELECT COUNT(*) FROM private_messages WHERE conversation_id=? AND sender_id<>? AND deleted=FALSE
                AND id>COALESCE((SELECT last_read_id FROM private_message_receipts WHERE conversation_id=? AND user_id=?),0)
                """, Long.class, id, owner, id, owner);
        return new Conversation(Long.toString(id), Long.toString(peer), name, unread);
    }

    public long peer(long owner, long id) {
        long peer = member(owner, id);
        if (!connections.canMessage(owner, peer)) throw missing();
        return peer;
    }

    private long member(long owner, long id) {
        return jdbc.query("""
                SELECT IF(member_low=?,member_high,member_low) FROM conversations
                WHERE id=? AND (member_low=? OR member_high=?)
                """, (r, n) -> r.getLong(1), owner, id, owner, owner).stream().findFirst().orElseThrow(MessageService::missing);
    }

    private long lockPeer(long owner, long id) {
        long peer = member(owner, id);
        connections.lockPair(owner, peer);
        if (!connections.canMessage(owner, peer)) throw missing();
        return peer;
    }

    public Page<Message> messages(long owner, long id, Long after, Long before, int page, int size) {
        long peer = peer(owner, id);
        if (after != null && before != null) throw invalid("Choose one message cursor.");
        boolean ascending = after != null;
        int offset = offset(page, size);
        var rows = jdbc.query("""
                SELECT m.*, COALESCE((SELECT last_read_id FROM private_message_receipts WHERE conversation_id=? AND user_id=?),0) read_id
                FROM private_messages m WHERE conversation_id=? AND (? IS NULL OR id>?) AND (? IS NULL OR id<?)
                ORDER BY id """ + (ascending ? " ASC" : " DESC") + " LIMIT ? OFFSET ?",
                (r, n) -> {
                    boolean deleted = r.getBoolean("deleted");
                    return new Message(r.getString("id"), r.getString("sender_id"), deleted ? null : r.getString("body"),
                            r.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                            r.getLong("sender_id") == owner && r.getLong("id") <= r.getLong("read_id"),
                            deleted ? null : r.getString("attachment_name"), deleted);
                }, id, peer, id, after, after, before, before, size + 1, offset);
        var result = new ArrayList<>(rows.subList(0, Math.min(rows.size(), size)));
        if (!ascending) Collections.reverse(result);
        return new Page<>(result, page, size, rows.size() > size);
    }

    public Message send(long owner, long id, String body, MultipartFile file) {
        String text = body == null ? "" : body.strip();
        if (text.length() > 4000 || text.isEmpty() && (file == null || file.isEmpty())) throw invalid("Enter a message or attach a file.");
        byte[] bytes = null;
        String name = null;
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > 5L * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
            name = file.getOriginalFilename();
            if (name == null || !name.matches("[\\p{L}\\p{N} ._()-]{1,150}\\.txt")) throw invalid("Attach a UTF-8 text file.");
            try (var stream = file.getInputStream()) {
                bytes = stream.readNBytes(5 * 1024 * 1024 + 1);
                if (bytes.length > 5 * 1024 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            } catch (java.io.IOException error) {
                throw invalid("Attach a UTF-8 text file.");
            }
        }
        final byte[] payload = bytes;
        final String filename = name;
        var key = payload == null ? null : UUID.randomUUID().toString();
        peer(owner, id);
        if (key != null) jdbc.update("INSERT INTO resource_object_cleanup VALUES (?, UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)", key);
        try {
            return transactions.execute(status -> {
                long peer = lockPeer(owner, id);
                if (key != null) {
                    jdbc.queryForObject("SELECT object_key FROM resource_object_cleanup WHERE object_key=? FOR UPDATE", String.class, key);
                    storage.put(key, new ByteArrayInputStream(payload), payload.length, "text/plain");
                }
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                var holder = new org.springframework.jdbc.support.GeneratedKeyHolder();
                jdbc.update(connection -> {
                    var stmt = connection.prepareStatement("""
                            INSERT INTO private_messages(conversation_id,sender_id,body,created_at,attachment_key,attachment_name)
                            VALUES (?,?,?,?,?,?)
                            """, java.sql.Statement.RETURN_GENERATED_KEYS);
                    stmt.setLong(1, id); stmt.setLong(2, owner); stmt.setString(3, text);
                    stmt.setObject(4, now); stmt.setString(5, key); stmt.setString(6, filename);
                    return stmt;
                }, holder);
                String messageId = String.valueOf(holder.getKey());
                if (key != null) jdbc.update("DELETE FROM resource_object_cleanup WHERE object_key=?", key);
                publish("MESSAGE", id, owner, peer, messageId);
                return new Message(messageId, Long.toString(owner), text, now.toInstant(ZoneOffset.UTC), false, filename, false);
            });
        } catch (RuntimeException failure) {
            if (key != null) jdbc.update("UPDATE resource_object_cleanup SET available_at=UTC_TIMESTAMP(6) WHERE object_key=?", key);
            throw failure;
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void read(long owner, long id, long message) {
        long peer = lockPeer(owner, id);
        requireMessage(id, message);
        jdbc.update("""
                INSERT INTO private_message_receipts VALUES (?,?,?) AS incoming
                ON DUPLICATE KEY UPDATE last_read_id=GREATEST(last_read_id,incoming.last_read_id)
                """, id, owner, message);
        publish("READ", id, owner, peer, Long.toString(message));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(long owner, long id, long message) {
        long peer = lockPeer(owner, id);
        var row = requireMessage(id, message);
        if (row.sender() != owner) throw missing();
        markDeleted(id, owner, peer, message, row.key());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void report(long owner, long id, long message, String reason) {
        lockPeer(owner, id);
        var row = requireMessage(id, message);
        if (row.sender() == owner) throw invalid("Choose a message from your partner.");
        if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid("Enter a reason up to 500 characters.");
        jdbc.update("INSERT IGNORE INTO private_message_reports VALUES (?,?,?,UTC_TIMESTAMP(6))", message, owner, reason.strip());
        reports.record(ReportIntake.Kind.PRIVATE_MESSAGE, message, owner, row.sender(), reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void hideReportedMessage(long message) {
        record Target(long conversation, long sender, long peer, String attachment) {}
        var target = jdbc.query("""
                SELECT m.conversation_id,m.sender_id,
                       IF(c.member_low=m.sender_id,c.member_high,c.member_low),m.attachment_key
                FROM private_messages m JOIN conversations c ON c.id=m.conversation_id
                WHERE m.id=? AND NOT m.deleted FOR UPDATE
                """, (r,i) -> new Target(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4)), message);
        if (target.isEmpty()) return;
        var row = target.getFirst();
        markDeleted(row.conversation(), row.sender(), row.peer(), message, row.attachment());
    }

    private void markDeleted(long conversation, long sender, long peer, long message, String attachment) {
        if (attachment != null) cleanup(attachment);
        jdbc.update("""
                UPDATE private_messages SET deleted=TRUE,attachment_key=NULL,attachment_name=NULL WHERE id=?
                """, message);
        publish("DELETED", conversation, sender, peer, Long.toString(message));
    }

    public Attachment attachment(long owner, long id, long message) {
        peer(owner, id);
        var row = requireMessage(id, message);
        if (row.key() == null) throw missing();
        return new Attachment(row.name(), storage.open(row.key()));
    }

    private AttachedMessage requireMessage(long id, long message) {
        return jdbc.query("""
                SELECT sender_id,attachment_key,attachment_name FROM private_messages WHERE conversation_id=? AND id=?
                """, (r, n) -> new AttachedMessage(r.getLong(1), r.getString(2), r.getString(3)), id, message)
                .stream().findFirst().orElseThrow(MessageService::missing);
    }

    @EventListener
    public void accountDeletion(AccountDeletionRequested deletion) {
        jdbc.update("""
                INSERT IGNORE INTO resource_object_cleanup(object_key,available_at)
                SELECT m.attachment_key,UTC_TIMESTAMP(6) FROM private_messages m
                JOIN conversations c ON c.id=m.conversation_id
                WHERE (c.member_low=? OR c.member_high=?) AND m.attachment_key IS NOT NULL
                """, deletion.userId(), deletion.userId());
    }

    private void cleanup(String key) {
        jdbc.update("INSERT IGNORE INTO resource_object_cleanup VALUES (?,UTC_TIMESTAMP(6))", key);
    }

    public void signal(String type, long conversation, long user) {
        publish(type, conversation, user, peer(user, conversation), null);
    }

    private void publish(String type, long id, long owner, long peer, String messageId) {
        events.publishEvent(new Event(type, Long.toString(id), Long.toString(owner), messageId, peer));
    }

    private static int offset(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("Choose valid pagination.");
        return page * size;
    }

    private static <T> Page<T> page(List<T> items, int page, int size) {
        return new Page<>(items.subList(0, Math.min(size, items.size())), page, size, items.size() > size);
    }

    static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }

    private record Pair(long id, long peer) {}
    private record AttachedMessage(long sender, String key, String name) {}
    public record Conversation(String id, String peerId, String peerName, long unreadCount) {}
    public record Message(String id, String senderId, String body, Instant createdAt, boolean read,
                          String attachmentName, boolean deleted) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    public record Attachment(String name, InputStream content) {}
    public record Event(String type, String conversationId, String userId, String messageId, long recipientId) {}
}
