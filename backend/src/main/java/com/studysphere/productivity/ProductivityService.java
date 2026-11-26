package com.studysphere.productivity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;

import com.studysphere.profile.ProfileService;
import com.studysphere.engagement.StudyActivityRecorded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductivityService {

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final ProfileService profiles;

    ProductivityService(JdbcTemplate jdbc, ProfileService profiles, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public Page<Exam> exams(long owner, String from, String to, int page, int size) {
        long offset = offset(page, size);
        LocalDate start = from == null ? LocalDate.of(1000, 1, 1) : date(from);
        LocalDate end = to == null ? LocalDate.of(9999, 12, 31) : date(to);
        if (end.isBefore(start)) {
            throw invalid("The date range ends before it starts.");
        }
        return page(examList(owner, start, end, size + 1, offset), page, size);
    }

    @Transactional(readOnly = true)
    public List<Exam> upcomingExams(long owner, LocalDate today) {
        return examList(owner, today, LocalDate.of(9999, 12, 31), 10, 0);
    }

    private List<Exam> examList(long owner, LocalDate from, LocalDate to, int count, long offset) {
        return jdbc.query("""
                SELECT id, subject_id, title, exam_date, time_zone, syllabus, goal, version
                FROM exams WHERE user_id = ? AND exam_date BETWEEN ? AND ?
                ORDER BY exam_date, id LIMIT ? OFFSET ?
                """, this::mapExam, owner, from, to, count, offset);
    }

    @Transactional(readOnly = true)
    public Exam exam(long owner, String id) {
        return findExam(owner, id(id));
    }

    @Transactional
    public Exam createExam(long owner, ExamInput input) {
        ExamData data = validateExam(input, null);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO exams (user_id, subject_id, title, exam_date, time_zone, syllabus, goal)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, owner);
            statement.setLong(2, data.subject());
            statement.setString(3, data.title());
            statement.setObject(4, data.date());
            statement.setString(5, data.zone());
            statement.setString(6, data.syllabus());
            statement.setString(7, data.goal());
            return statement;
        }, key);
        long id = key.getKey().longValue();
        replaceTopics(id, data.topics());
        return findExam(owner, id);
    }

    @Transactional
    public Exam updateExam(long owner, String examId, ExamInput input) {
        long id = id(examId);
        Exam current = findExam(owner, id);
        if (input.version() == null) {
            throw invalid("Send the current exam version.");
        }
        ExamData data = validateExam(input, current);
        int writes = jdbc.update("""
                UPDATE exams SET subject_id = ?, title = ?, exam_date = ?, time_zone = ?,
                    syllabus = ?, goal = ?, version = version + 1
                WHERE id = ? AND user_id = ? AND version = ?
                """, data.subject(), data.title(), data.date(), data.zone(), data.syllabus(),
                data.goal(), id, owner, input.version());
        if (writes == 0) {
            throw new ProductivityFailure(409, "VERSION_CONFLICT", "Reload the exam before editing it.");
        }
        replaceTopics(id, data.topics());
        return findExam(owner, id);
    }

    @Transactional
    public void deleteExam(long owner, String examId) {
        if (jdbc.update("DELETE FROM exams WHERE id = ? AND user_id = ?", id(examId), owner) == 0) {
            throw notFound();
        }
    }

    private ExamData validateExam(ExamInput input, Exam current) {
        String subjectId = supplied(input.subjectId(), current == null ? null : current.subjectId());
        if (subjectId == null) {
            throw invalid("Choose an exam subject.");
        }
        long subject = subject(subjectId);
        String title = requiredText(supplied(input.title(), current == null ? null : current.title()), 150);
        LocalDate date = date(supplied(input.examDate(), current == null ? null : current.examDate().toString()));
        String zone = zone(supplied(input.timeZone(), current == null ? null : current.timeZone())).getId();
        String syllabus = optionalText(supplied(input.syllabus(), current == null ? null : current.syllabus()), 4000);
        String goal = optionalText(supplied(input.goal(), current == null ? null : current.goal()), 500);

        List<String> topicIds = supplied(input.topicIds(), current == null ? List.of() : current.topicIds());
        if (topicIds.size() > 100) {
            throw invalid("Select at most 100 exam topics.");
        }
        var uniqueTopics = new LinkedHashSet<Long>();
        for (String topicId : topicIds) {
            long topic = id(topicId);
            if (!exists("SELECT COUNT(*) FROM topics WHERE subject_id = ? AND id = ?", subject, topic)) {
                throw invalid("Every exam topic must belong to the selected subject.");
            }
            if (!uniqueTopics.add(topic)) {
                throw invalid("Exam topics cannot contain duplicates.");
            }
        }
        return new ExamData(subject, title, date, zone, syllabus, goal, List.copyOf(uniqueTopics));
    }

    private void replaceTopics(long exam, List<Long> topics) {
        jdbc.update("DELETE FROM exam_topics WHERE exam_id = ?", exam);
        jdbc.batchUpdate("INSERT INTO exam_topics (exam_id, topic_id) VALUES (?, ?)",
                topics.stream().map(topic -> new Object[]{exam, topic}).toList());
    }

    private Exam findExam(long owner, long id) {
        return jdbc.query("""
                SELECT id, subject_id, title, exam_date, time_zone, syllabus, goal, version
                FROM exams WHERE user_id = ? AND id = ?
                """, this::mapExam, owner, id).stream().findFirst().orElseThrow(ProductivityService::notFound);
    }

    private Exam mapExam(ResultSet result, int row) throws SQLException {
        long id = result.getLong("id");
        List<String> topics = jdbc.query("SELECT topic_id FROM exam_topics WHERE exam_id = ? ORDER BY topic_id",
                (topic, index) -> Long.toString(topic.getLong(1)), id);
        return new Exam(Long.toString(id), Long.toString(result.getLong("subject_id")), result.getString("title"),
                result.getObject("exam_date", LocalDate.class), result.getString("time_zone"), topics,
                result.getString("syllabus"), result.getString("goal"), result.getLong("version"));
    }

    @Transactional(readOnly = true)
    public Page<FocusSession> sessions(long owner, String state, int page, int size) {
        long offset = offset(page, size);
        if (state != null && !state.equals("ACTIVE") && !state.equals("COMPLETED")) {
            throw invalid("Choose a valid focus state.");
        }
        List<FocusSession> sessions = jdbc.query("""
                SELECT id, subject_id, focus_minutes, break_minutes, state, started_at, completed_at, version
                FROM focus_sessions WHERE user_id = ? AND (? IS NULL OR state = ?)
                ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?
                """, this::mapFocus, owner, state, state, size + 1, offset);
        return page(sessions, page, size);
    }

    @Transactional
    public FocusSession start(long owner, FocusInput input) {
        int focus = duration(input.focusMinutes(), 1, 180, "Focus duration");
        int pause = duration(input.breakMinutes(), 0, 60, "Break duration");
        Long subject = input.subjectId() == null || input.subjectId().isBlank()
                ? null : subject(input.subjectId());
        if (jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE", (row, index) -> row.getLong(1), owner)
                .isEmpty()) {
            throw notFound();
        }
        if (!jdbc.query("""
                SELECT id FROM focus_sessions WHERE user_id = ? AND state = 'ACTIVE' FOR UPDATE
                """, (row, index) -> row.getLong(1), owner).isEmpty()) {
            throw new ProductivityFailure(409, "ACTIVE_FOCUS_SESSION", "Finish the active session first.");
        }
        var key = new GeneratedKeyHolder();
        LocalDateTime start = utc(now());
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO focus_sessions (user_id, subject_id, focus_minutes, break_minutes, started_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, owner);
            statement.setObject(2, subject);
            statement.setInt(3, focus);
            statement.setInt(4, pause);
            statement.setObject(5, start);
            return statement;
        }, key);
        return findFocus(owner, key.getKey().longValue(), false);
    }

    @Transactional(readOnly = true)
    public FocusSession session(long owner, String id) {
        return findFocus(owner, id(id), false);
    }

    @Transactional
    public FocusSession complete(long owner, String sessionId) {
        long id = id(sessionId);
        FocusSession session = findFocus(owner, id, true);
        if (session.completedAt() != null) {
            return session;
        }
        Instant completedAt = now();
        if (completedAt.isBefore(session.startedAt())) {
            completedAt = session.startedAt();
        }
        long elapsed = Duration.between(session.startedAt(), completedAt).toSeconds();
        long loggedSeconds = Math.min(elapsed, session.focusMinutes() * 60L);
        jdbc.update("""
                UPDATE focus_sessions SET state = 'COMPLETED', completed_at = ?, version = version + 1
                WHERE user_id = ? AND id = ?
                """, utc(completedAt), owner, id);
        jdbc.update("INSERT INTO study_session_logs (focus_session_id, duration_seconds, recorded_at) VALUES (?, ?, ?)",
                id, loggedSeconds, utc(completedAt));
        if (loggedSeconds > 0) {
            events.publishEvent(new StudyActivityRecorded(owner, StudyActivityRecorded.Kind.FOCUS, id, completedAt));
        }
        return findFocus(owner, id, false);
    }

    @Transactional(readOnly = true)
    public FocusSummary focusSummary(long owner) {
        var preferences = profiles.preferences(owner);
        ZoneId zone = preferences.timeZone() == null ? ZoneId.of("UTC") : zone(preferences.timeZone());
        LocalDate date = LocalDate.now(zone);
        var start = utc(date.atStartOfDay(zone).toInstant());
        var end = utc(date.plusDays(1).atStartOfDay(zone).toInstant());
        Long seconds = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.duration_seconds), 0)
                FROM study_session_logs l JOIN focus_sessions f ON f.id = l.focus_session_id
                WHERE f.user_id = ? AND l.recorded_at >= ? AND l.recorded_at < ?
                """, Long.class, owner, start, end);
        return new FocusSummary(date, zone.getId(), seconds / 60, preferences.dailyFocusGoalMinutes());
    }

    private FocusSession findFocus(long owner, long id, boolean lock) {
        String sql = """
                SELECT id, subject_id, focus_minutes, break_minutes, state, started_at, completed_at, version
                FROM focus_sessions WHERE user_id = ? AND id = ?
                """;
        return jdbc.query(lock ? sql + " FOR UPDATE" : sql, this::mapFocus, owner, id)
                .stream().findFirst().orElseThrow(ProductivityService::notFound);
    }

    private FocusSession mapFocus(ResultSet row, int index) throws SQLException {
        Long subject = row.getObject("subject_id", Long.class);
        LocalDateTime completion = row.getObject("completed_at", LocalDateTime.class);
        return new FocusSession(Long.toString(row.getLong("id")), subject == null ? null : subject.toString(),
                row.getInt("focus_minutes"), row.getInt("break_minutes"), row.getString("state"),
                row.getObject("started_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                completion == null ? null : completion.toInstant(ZoneOffset.UTC), row.getLong("version"));
    }

    private long subject(String subjectId) {
        long subject = id(subjectId);
        if (!exists("SELECT COUNT(*) FROM subjects WHERE id = ?", subject)) {
            throw invalid("Choose an existing subject.");
        }
        return subject;
    }

    private boolean exists(String sql, Object... params) {
        Integer count = jdbc.queryForObject(sql, Integer.class, params);
        return count != null && count > 0;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static int duration(Integer value, int min, int max, String label) {
        if (value == null || value < min || value > max) {
            throw invalid(label + " must be between " + min + " and " + max + " minutes.");
        }
        return value;
    }

    private static long id(String value) {
        try {
            if (value != null && value.matches("[1-9][0-9]{0,18}")) {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException ignored) {
            // The numeric value exceeds the database ID range.
        }
        throw invalid("Send a valid resource ID.");
    }

    private static LocalDate date(String value) {
        try {
            LocalDate date = value == null ? null : LocalDate.parse(value);
            if (date != null && date.getYear() >= 1000 && date.getYear() <= 9999) {
                return date;
            }
        } catch (DateTimeException ignored) {
            // The value is outside ISO calendar date syntax.
        }
        throw invalid("Send a valid calendar date.");
    }

    private static ZoneId zone(String value) {
        if (value == null || !ZoneId.getAvailableZoneIds().contains(value)) {
            throw invalid("Choose an IANA time zone.");
        }
        return ZoneId.of(value);
    }

    private static String requiredText(String text, int maximum) {
        String cleaned = optionalText(text, maximum);
        if (cleaned == null) {
            throw invalid("An exam title is required.");
        }
        return cleaned;
    }

    private static String optionalText(String text, int maximum) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.strip();
        if (cleaned.codePointCount(0, cleaned.length()) > maximum) {
            throw invalid("Text must be no longer than " + maximum + " characters.");
        }
        return cleaned;
    }

    private static long offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw invalid("Use a nonnegative page and a page size from 1 to 100.");
        }
        return (long) page * size;
    }

    private static <T> Page<T> page(List<T> results, int page, int size) {
        boolean hasNext = results.size() > size;
        return new Page<>(List.copyOf(results.subList(0, Math.min(results.size(), size))), page, size, hasNext);
    }

    private static <T> T supplied(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static ProductivityFailure invalid(String message) {
        return new ProductivityFailure(400, "INVALID_PRODUCTIVITY_REQUEST", message);
    }

    private static ProductivityFailure notFound() {
        return new ProductivityFailure(404, "NOT_FOUND", "This resource could not be found.");
    }

    public record ExamInput(String subjectId, String title, String examDate, String timeZone, List<String> topicIds,
                            String syllabus, String goal, Long version) {}
    public record Exam(String id, String subjectId, String title, LocalDate examDate, String timeZone,
                       List<String> topicIds, String syllabus, String goal, long version) {}
    private record ExamData(long subject, String title, LocalDate date, String zone, String syllabus, String goal,
                            List<Long> topics) {}
    public record FocusInput(String subjectId, Integer focusMinutes, Integer breakMinutes) {}
    public record FocusSession(String id, String subjectId, int focusMinutes, int breakMinutes, String state,
                               Instant startedAt, Instant completedAt, long version) {}
    public record FocusSummary(LocalDate date, String timeZone, long completedMinutes, Integer dailyFocusGoalMinutes) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
}
