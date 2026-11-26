package com.studysphere.mentor;

import com.studysphere.mentor.provider.MentorProvider;
import com.studysphere.mentor.provider.MentorProvider.Turn;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class MentorService {
    static final int MAX_OUTPUT = 8_000;
    private static final int MAX_CONTEXT = 12_000;
    private static final Set<String> MODES = Set.of("EXPLAIN", "SIMPLIFY", "COMPARE", "SUMMARIZE",
            "PRACTICE", "QUIZ", "INTERVIEW", "HINTS", "EXAM");

    private static final RowMapper<Conversation> CONVERSATION = (row, index) -> new Conversation(
            row.getString("id"), row.getString("title"),
            row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    private static final RowMapper<Message> MESSAGE = (row, index) -> new Message(
            row.getString("id"), row.getString("role"), row.getString("content"),
            row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;
    private final MentorProvider provider;
    private final int minuteLimit;
    private final int dailyLimit;

    MentorService(JdbcTemplate jdbc, MentorProvider provider,
            @Value("${studysphere.mentor.requests-per-minute:6}") int minuteLimit,
            @Value("${studysphere.mentor.requests-per-day:40}") int dailyLimit) {
        this.jdbc = jdbc;
        this.provider = provider;
        if (minuteLimit < 1 || dailyLimit < 1) throw new IllegalArgumentException("Mentor request limits must be positive.");
        this.minuteLimit = minuteLimit;
        this.dailyLimit = dailyLimit;
    }

    Page<Conversation> conversations(long owner, int page, int size) {
        return page(jdbc.query("""
                SELECT id,title,updated_at FROM ai_conversations WHERE user_id=?
                ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?
                """, CONVERSATION, owner, size + 1, offset(page, size)), page, size);
    }

    Conversation conversation(long owner, long id) {
        return jdbc.query("SELECT id,title,updated_at FROM ai_conversations WHERE id=? AND user_id=?",
                CONVERSATION, id, owner).stream().findFirst().orElseThrow(MentorService::missing);
    }

    @Transactional
    public Conversation create(long owner, String title) {
        String checked = title == null || title.isBlank() ? "General study" : text(title, 160, "Title");
        long id = insert("INSERT INTO ai_conversations (user_id,title,updated_at) VALUES (?,?,UTC_TIMESTAMP(6))",
                owner, checked);
        return conversation(owner, id);
    }

    @Transactional
    public Conversation rename(long owner, long id, String title) {
        if (jdbc.update("""
                UPDATE ai_conversations SET title=?,updated_at=UTC_TIMESTAMP(6) WHERE id=? AND user_id=?
                """, text(title, 160, "Title"), id, owner) == 0) throw missing();
        return conversation(owner, id);
    }

    @Transactional
    public void delete(long owner, long id) {
        lockOwner(owner);
        if (jdbc.update("DELETE FROM ai_conversations WHERE id=? AND user_id=?", id, owner) == 0) throw missing();
    }

    Page<Message> messages(long owner, long id, int page, int size) {
        conversation(owner, id);
        return page(jdbc.query("""
                SELECT m.id,m.role,m.content,m.created_at FROM ai_messages m
                JOIN ai_conversations c ON c.id=m.conversation_id
                WHERE c.id=? AND c.user_id=? ORDER BY m.id DESC LIMIT ? OFFSET ?
                """, MESSAGE, id, owner, size + 1, offset(page, size)), page, size);
    }

    @Transactional
    public Run begin(long owner, long id, String prompt, String mode, int timeoutSeconds) {
        String input = text(prompt, 4_000, "Message");
        if (mode == null || !MODES.contains(mode)) throw invalid("Choose a valid mentor mode.");
        lockOwner(owner);
        conversation(owner, id);
        if (!provider.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The mentor provider is not configured.");
        }
        int lastMinute = count("SELECT COUNT(*) FROM ai_usage_logs WHERE user_id=? AND started_at >= UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE", owner);
        int today = count("SELECT COUNT(*) FROM ai_usage_logs WHERE user_id=? AND started_at >= UTC_DATE()", owner);
        if (lastMinute >= minuteLimit || today >= dailyLimit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Mentor request limit reached.");
        }
        if (count("""
                SELECT COUNT(*) FROM ai_usage_logs WHERE conversation_id=? AND status='RUNNING'
                    AND started_at >= TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6))
                """, id, -timeoutSeconds - 10) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A response is already streaming for this conversation.");
        }

        addMessage(id, "user", input);
        touchConversation(id);
        var turns = context(id);
        int inputCharacters = turns.stream().mapToInt(turn -> turn.text().length()).sum();
        long usage = insert("""
                INSERT INTO ai_usage_logs(user_id,conversation_id,provider,mode,status,input_characters,started_at)
                VALUES (?,?,?,?,'RUNNING',?,UTC_TIMESTAMP(6))
                """, owner, id, provider.id(), mode, inputCharacters);
        return new Run(owner, id, usage, mode, turns);
    }

    private List<Turn> context(long id) {
        var latest = jdbc.query("""
                SELECT role,content FROM ai_messages WHERE conversation_id=? ORDER BY id DESC LIMIT 20
                """, (row, index) -> new Turn(row.getString("role"), row.getString("content")), id);
        var turns = new ArrayList<Turn>();
        int remaining = MAX_CONTEXT;
        for (var turn : latest) {
            if (turn.text().length() > remaining) break;
            turns.add(turn);
            remaining -= turn.text().length();
        }
        Collections.reverse(turns);
        return List.copyOf(turns);
    }

    @Transactional
    public Message finish(Run run, String output) {
        lockOwner(run.owner());
        conversation(run.owner(), run.conversation());
        var assistant = addMessage(run.conversation(), "assistant", output);
        touchConversation(run.conversation());
        close(run, "COMPLETE", output.length());
        return assistant;
    }

    void close(Run run, String status, int characters) {
        jdbc.update("""
                UPDATE ai_usage_logs SET status=?,output_characters=?,finished_at=UTC_TIMESTAMP(6)
                WHERE id=? AND user_id=? AND status='RUNNING'
                """, status, characters, run.usage(), run.owner());
    }

    private void touchConversation(long id) {
        jdbc.update("UPDATE ai_conversations SET updated_at=UTC_TIMESTAMP(6) WHERE id=?", id);
    }

    private Message addMessage(long id, String role, String content) {
        long message = insert("""
                INSERT INTO ai_messages(conversation_id,role,content,created_at) VALUES (?,?,?,UTC_TIMESTAMP(6))
                """, id, role, content);
        return jdbc.queryForObject("SELECT id,role,content,created_at FROM ai_messages WHERE id=?", MESSAGE, message);
    }

    private void lockOwner(long owner) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE", Long.class, owner).isEmpty()) {
            throw missing();
        }
    }

    private long insert(String sql, Object... arguments) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    private int count(String sql, Object... arguments) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, arguments));
    }

    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("Invalid page or size.");
        return (long) page * size;
    }

    private static <T> Page<T> page(List<T> rows, int page, int size) {
        return new Page<>(List.copyOf(rows.subList(0, Math.min(size, rows.size()))), page, size, rows.size() > size);
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw invalid(field + " must contain between 1 and " + max + " characters.");
        }
        return value.strip();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found.");
    }

    record Conversation(String id, String title, Instant updatedAt) {}
    record Message(String id, String role, String content, Instant createdAt) {}
    record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    record Run(long owner, long conversation, long usage, String mode, List<Turn> turns) {}
}
