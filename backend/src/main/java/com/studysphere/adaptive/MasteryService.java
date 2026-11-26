package com.studysphere.adaptive;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MasteryService {
    private static final Set<String> FEEDBACK = Set.of("ACCEPTED", "DISMISSED", "HELPFUL", "NOT_HELPFUL");
    private static final String TOPIC_EVIDENCE = """
            WITH observed AS (
                SELECT topic_id, correct AS successful FROM quiz_evidence WHERE user_id = :owner
                UNION ALL
                SELECT d.topic_id, v.rating IN ('GOOD','EASY') AS successful
                FROM flashcard_reviews v JOIN flashcards c ON c.id = v.card_id
                JOIN flashcard_decks d ON d.id = c.deck_id
                WHERE d.user_id = :owner AND d.topic_id IS NOT NULL
            ), totals AS (
                SELECT topic_id, COUNT(*) AS evidence_count, SUM(successful) AS successful
                FROM observed GROUP BY topic_id
            ), evidence AS (
                SELECT t.id AS topic_id, t.name AS topic_name, s.id AS subject_id, s.name AS subject_name,
                       COALESCE(e.evidence_count, 0) AS evidence_count,
                       CASE WHEN e.evidence_count >= 3 THEN ROUND(e.successful * 100.0 / e.evidence_count) END AS score
                FROM topics t JOIN subjects s ON t.subject_id = s.id
                LEFT JOIN totals e ON e.topic_id = t.id
                WHERE e.topic_id IS NOT NULL OR EXISTS (
                    SELECT 1 FROM user_subjects u WHERE u.user_id = :owner AND u.subject_id = s.id
                )
            )
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public MasteryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<Topic> topics(long owner, int page, int size) {
        var results = jdbc.query(TOPIC_EVIDENCE + """
                SELECT * FROM evidence
                ORDER BY score IS NULL, score, subject_name, topic_name, topic_id
                LIMIT :limit OFFSET :offset
                """, parameters(owner, page, size), (row, index) -> {
                    Integer score = row.getObject("score", Integer.class);
                    return new Topic(row.getString("topic_id"), row.getString("topic_name"),
                            row.getString("subject_id"), row.getString("subject_name"),
                            row.getLong("evidence_count"), score, state(score));
                });
        return page(results, page, size);
    }

    public Page<Recommendation> recommendations(long owner, int page, int size) {
        var results = jdbc.query(TOPIC_EVIDENCE + """
                SELECT e.* FROM evidence e
                LEFT JOIN recommendation_feedback f ON f.user_id = :owner AND f.topic_id = e.topic_id
                WHERE (e.score < 80 OR e.score IS NULL)
                  AND (f.status IS NULL OR f.status NOT IN ('DISMISSED', 'ACCEPTED'))
                ORDER BY e.score IS NULL, e.score, e.evidence_count DESC, e.topic_id
                LIMIT :limit OFFSET :offset
                """, parameters(owner, page, size), (row, index) ->
                recommendation(row.getString("topic_id"), row.getString("topic_name"),
                        row.getLong("evidence_count"), row.getObject("score", Integer.class)));
        return page(results, page, size);
    }

    public void feedback(long owner, long recommendation, String status) {
        if (status == null || !FEEDBACK.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid feedback status.");
        }
        int affected = jdbc.update("""
                INSERT INTO recommendation_feedback (user_id, topic_id, status, updated_at)
                SELECT :owner, t.id, :status, UTC_TIMESTAMP(6) FROM topics t
                WHERE t.id = :topic AND (
                    EXISTS (SELECT 1 FROM user_subjects s WHERE s.user_id = :owner AND s.subject_id = t.subject_id)
                    OR EXISTS (SELECT 1 FROM quiz_evidence q WHERE q.user_id = :owner AND q.topic_id = t.id)
                    OR EXISTS (
                        SELECT 1 FROM flashcard_decks d JOIN flashcards c ON c.deck_id = d.id
                        JOIN flashcard_reviews r ON r.card_id = c.id
                        WHERE d.user_id = :owner AND d.topic_id = t.id
                    )
                )
                ON DUPLICATE KEY UPDATE status = :status, updated_at = UTC_TIMESTAMP(6)
                """, Map.of("owner", owner, "topic", recommendation, "status", status));
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recommendation not found.");
        }
    }

    private static Map<String, Object> parameters(long owner, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or page size.");
        }
        return Map.of("owner", owner, "limit", size + 1, "offset", (long) page * size);
    }

    private static <T> Page<T> page(List<T> results, int page, int size) {
        return new Page<>(List.copyOf(results.subList(0, Math.min(results.size(), size))),
                page, size, results.size() > size);
    }

    static String state(Integer score) {
        if (score == null) return "INSUFFICIENT";
        if (score < 60) return "WEAK";
        return score < 80 ? "DEVELOPING" : "MASTERED";
    }

    static Recommendation recommendation(String id, String topic, long count, Integer score) {
        if (score == null) {
            return new Recommendation(id, id, topic, "STUDY_TOPIC", "Practice " + topic,
                    "Only " + count + " of 3 required observations are recorded. Try practice questions to establish a baseline.");
        }
        if (score < 60) {
            return new Recommendation(id, id, topic, "REVISION", "Review " + topic,
                    "Your success rate is " + score + "% across " + count + " recorded responses.");
        }
        return new Recommendation(id, id, topic, "QUIZ", "Quiz yourself on " + topic,
                "Your success rate is " + score + "% across " + count + " recorded responses.");
    }

    public record Topic(String topicId, String topicName, String subjectId, String subjectName,
                        long evidenceCount, Integer score, String state) {}
    public record Recommendation(String id, String topicId, String topicName, String kind, String title, String reason) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
}
