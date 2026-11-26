package com.studysphere.quiz;

import com.studysphere.quiz.QuizRules.Question;
import com.studysphere.quiz.QuizRules.Result;
import com.studysphere.quiz.QuizRules.Selection;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import com.studysphere.engagement.StudyActivityRecorded;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class QuizService {
    private static final TypeReference<List<Question>> QUESTIONS = new TypeReference<>() {};
    private static final TypeReference<List<Result>> RESULTS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    QuizService(JdbcTemplate jdbc, ObjectMapper json, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
        this.json = json;
    }

    Detail create(long owner, String requestedTitle, List<Question> requested) {
        var title = QuizRules.text(requestedTitle, 150);
        var questions = QuizRules.validate(requested);
        for (var question : questions) if (question.topicId() != null) topic(id(question.topicId()));
        long quiz = insert("INSERT INTO quizzes(user_id,title,questions) VALUES(?,?,?)", owner, title, json.writeValueAsString(questions));
        return detail(owner, quiz);
    }

    public String createRoomQuestion(long owner, String title, String prompt, List<String> options,
                                     Integer correctIndex, String explanation) {
        return create(owner, title, List.of(new Question(prompt, "MCQ", options, correctIndex, explanation, null))).id();
    }

    Page<Summary> list(long owner, int page, int size) {
        page(page, size);
        var rows = jdbc.query("""
                SELECT id,title,JSON_LENGTH(questions) AS question_count FROM quizzes
                WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?
                """, (rs, i) -> new Summary(rs.getString(1), rs.getString(2), rs.getInt(3)), owner, size + 1, (long) page * size);
        return page(rows, page, size);
    }

    public List<Summary> summaries(long owner) {
        return list(owner, 0, 10).items();
    }

    Detail detail(long owner, long quiz) {
        var row = quiz(owner, quiz);
        return new Detail(Long.toString(quiz), row.title(), IntStream.range(0, row.questions().size()).mapToObj(i -> {
            Question q = row.questions().get(i);
            return new PublicQuestion(Integer.toString(i + 1), q.prompt(), q.type(), q.options());
        }).toList());
    }

    @Transactional
    Attempt start(long owner, long quiz) {
        var row = quiz(owner, quiz);
        long id = insert("INSERT INTO quiz_attempts(quiz_id,user_id) VALUES(?,?)", quiz, owner);
        return new Attempt(Long.toString(id), Long.toString(quiz), "IN_PROGRESS", null, row.questions().size(), List.of());
    }

    Page<Attempt> attempts(long owner, long quiz, int page, int size) {
        page(page, size);
        int total = quiz(owner, quiz).questions().size();
        var rows = jdbc.query("""
                SELECT id,quiz_id,score,results FROM quiz_attempts
                WHERE user_id=? AND quiz_id=? ORDER BY id DESC LIMIT ? OFFSET ?
                """, (rs, i) -> attempt(rs, total), owner, quiz, size + 1, (long) page * size);
        return page(rows, page, size);
    }

    Attempt attempt(long owner, long id) {
        return first("""
                SELECT a.id,a.quiz_id,a.score,a.results,JSON_LENGTH(q.questions) total
                FROM quiz_attempts a JOIN quizzes q ON q.id=a.quiz_id WHERE a.user_id=? AND a.id=?
                """, (rs, i) -> attempt(rs, rs.getInt("total")), owner, id);
    }

    @Transactional
    Attempt submit(long owner, long id, List<Selection> answers) {
        long quiz = first("SELECT quiz_id FROM quiz_attempts WHERE user_id=? AND id=? FOR UPDATE",
                (rs, i) -> rs.getLong(1), owner, id);
        Attempt previous = attempt(owner, id);
        if (previous.score() != null) return previous;
        var questions = quiz(owner, quiz).questions();
        var results = QuizRules.grade(questions, answers);
        int score = (int) results.stream().filter(Result::correct).count();
        jdbc.update("UPDATE quiz_attempts SET score=?,results=?,submitted_at=UTC_TIMESTAMP(6) WHERE id=?",
                score, json.writeValueAsString(results), id);
        for (int i = 0; i < questions.size(); i++) {
            var topic = questions.get(i).topicId();
            if (topic != null) jdbc.update("""
                    INSERT INTO quiz_evidence(attempt_id,question_index,user_id,topic_id,correct,observed_at)
                    VALUES(?,?,?,?,?,UTC_TIMESTAMP(6))
                    """, id, i, owner, id(topic), results.get(i).correct());
        }
        events.publishEvent(new StudyActivityRecorded(owner, StudyActivityRecorded.Kind.QUIZ, id, Instant.now()));
        return attempt(owner, id);
    }

    String topic(long id) {
        return first("""
                SELECT CONCAT(s.name, ': ', t.name) FROM topics t JOIN subjects s ON t.subject_id=s.id WHERE t.id=?
                """, (rs, i) -> rs.getString(1), id);
    }

    private QuizRow quiz(long owner, long quiz) {
        return first("SELECT title,questions FROM quizzes WHERE id=? AND user_id=?",
                (rs, i) -> new QuizRow(rs.getString(1), json.readValue(rs.getString(2), QUESTIONS)), quiz, owner);
    }

    private Attempt attempt(java.sql.ResultSet rs, int total) throws java.sql.SQLException {
        var score = rs.getObject("score", Integer.class);
        return new Attempt(rs.getString("id"), rs.getString("quiz_id"), score == null ? "IN_PROGRESS" : "SUBMITTED",
                score, total, score == null ? List.of() : json.readValue(rs.getString("results"), RESULTS));
    }

    private <T> T first(String sql, RowMapper<T> mapper, Object... args) {
        return jdbc.query(sql, mapper, args).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quiz item was not found."));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return java.util.Objects.requireNonNull(key.getKey()).longValue();
    }

    static long id(String input) {
        try {
            if (!input.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(input);
        } catch (NumberFormatException | NullPointerException ignored) {
            throw QuizRules.invalid("Invalid identifier.");
        }
    }

    private static void page(int page, int size) {
        if (page < 0 || page > 10_000 || size < 1 || size > 100) throw QuizRules.invalid("Invalid page.");
    }

    private static <T> Page<T> page(List<T> rows, int page, int size) {
        return new Page<>(rows.stream().limit(size).toList(), page, size, rows.size() > size);
    }

    private record QuizRow(String title, List<Question> questions) {}
    record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    public record Summary(String id, String title, int questionCount) {}
    record Detail(String id, String title, List<PublicQuestion> questions) {}
    record PublicQuestion(String id, String prompt, String type, List<String> options) {}
    record Attempt(String id, String quizId, String status, Integer score, int total, List<Result> results) {}
}
