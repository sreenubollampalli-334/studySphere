package com.studysphere.cards;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import com.studysphere.engagement.StudyActivityRecorded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FlashcardService {
    private static final String ACCESS = """
            d.user_id=? AND (d.document_id IS NULL OR EXISTS(
                SELECT 1 FROM documents doc JOIN resources r ON r.id=doc.resource_id
                WHERE doc.id=d.document_id AND doc.status='READY' AND (r.user_id=d.user_id OR EXISTS(
                    SELECT 1 FROM resource_permissions p
                    WHERE p.resource_id=r.id AND p.user_id=d.user_id))))
            """;
    private static final String DECK = """
            SELECT d.*, (SELECT COUNT(*) FROM flashcards c WHERE c.deck_id=d.id) AS card_count
            FROM flashcard_decks d WHERE
            """;
    private static final String CARD = """
            SELECT c.* FROM flashcards c JOIN flashcard_decks d ON c.deck_id=d.id WHERE
            """;
    private static final RowMapper<Deck> DECK_ROW = (rs, row) -> new Deck(
            rs.getString("id"), rs.getString("title"), rs.getString("subject_id"), rs.getString("topic_id"),
            rs.getString("document_id"), rs.getInt("card_count"), null);
    private static final RowMapper<Card> CARD_ROW = (rs, row) -> new Card(rs.getString("id"),
            rs.getString("deck_id"), rs.getString("front"), rs.getString("back"), instant(rs, "due_at"),
            rs.getInt("interval_days"));
    private static final RowMapper<Review> REVIEW_ROW = (rs, row) -> new Review(rs.getString("id"),
            rs.getString("card_id"), rs.getString("rating"), instant(rs, "reviewed_at"),
            instant(rs, "due_at"), rs.getInt("interval_days"));
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    FlashcardService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public Page<Deck> list(long owner, int page, int size) {
        long offset = offset(page, size);
        return page(jdbc.query(DECK + ACCESS + " ORDER BY d.id DESC LIMIT ? OFFSET ?",
                DECK_ROW, owner, size + 1, offset), page, size);
    }

    public Deck detail(long owner, long id) {
        Deck deck = deck(owner, id);
        List<Card> cards = jdbc.query(CARD + ACCESS + " AND d.id=? ORDER BY c.id", CARD_ROW, owner, id);
        return new Deck(deck.id(), deck.title(), deck.subjectId(), deck.topicId(), deck.documentId(),
                deck.cardCount(), cards);
    }

    @Transactional
    public Deck create(long owner, DeckInput input) {
        if (input == null) throw invalid("Deck data is required.");
        String title = field(input.title(), 160, "Deck title");
        Source source = source(owner, input.subjectId(), input.topicId(), input.documentId());
        List<CardInput> cards = input.cards() == null ? List.of() : input.cards();
        if (cards.size() > 100) throw invalid("A deck can hold at most 100 cards.");
        long id = insert("""
                INSERT INTO flashcard_decks(user_id,title,subject_id,topic_id,document_id,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6))
                """, owner, title, source.subject(), source.topic(), source.document());
        for (CardInput card : cards) insertCard(id, card);
        return detail(owner, id);
    }

    @Transactional
    public Card addCard(long owner, long deckId, CardInput input) {
        lockedDeck(owner, deckId);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM flashcards WHERE deck_id=?", Integer.class, deckId);
        if (count != null && count >= 100) throw invalid("A deck can hold at most 100 cards.");
        long id = insertCard(deckId, input);
        return card(owner, id, false);
    }

    @Transactional
    public void delete(long owner, long id) {
        // Ownership is sufficient for deletion after a source share has been revoked.
        if (jdbc.update("DELETE FROM flashcard_decks WHERE id=? AND user_id=?", id, owner) != 1) throw missing();
    }

    public Page<Card> due(long owner, int page, int size) {
        long offset = offset(page, size);
        var cards = jdbc.query(CARD + ACCESS + " AND c.due_at<=UTC_TIMESTAMP(6) ORDER BY c.due_at,c.id LIMIT ? OFFSET ?",
                CARD_ROW, owner, size + 1, offset);
        return page(cards, page, size);
    }

    public Page<Review> reviews(long owner, long id, int page, int size) {
        long offset = offset(page, size);
        card(owner, id, false);
        var reviews = jdbc.query("""
                SELECT v.* FROM flashcard_reviews v JOIN flashcards c ON c.id=v.card_id
                JOIN flashcard_decks d ON d.id=c.deck_id WHERE
                """ + ACCESS + " AND c.id=? ORDER BY v.id DESC LIMIT ? OFFSET ?",
                REVIEW_ROW, owner, id, size + 1, offset);
        return page(reviews, page, size);
    }

    @Transactional
    public Review review(long owner, long id, ReviewInput input) {
        if (input == null) throw invalid("Review data is required.");
        byte[] key = requestKey(input.requestId());
        FlashcardSchedule.Rating rating = rating(input.rating());
        Card card = card(owner, id, true);
        var previous = jdbc.query("""
                SELECT * FROM flashcard_reviews WHERE card_id=? AND request_id=? FOR UPDATE
                """, REVIEW_ROW, id, key);
        if (!previous.isEmpty()) {
            if (!previous.getFirst().rating().equals(rating.name())) {
                throw conflict("This review request ID was already used with a different rating.");
            }
            return previous.getFirst();
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (card.dueAt().isAfter(now)) throw conflict("This card is not due yet.");
        var next = FlashcardSchedule.next(now, card.intervalDays(), rating);
        long reviewId = insert("""
                INSERT INTO flashcard_reviews(card_id,request_id,rating,reviewed_at,due_at,interval_days)
                VALUES (?,?,?,?,?,?)
                """, id, key, rating.name(), utc(now), utc(next.dueAt()), next.intervalDays());
        jdbc.update("UPDATE flashcards SET due_at=?,interval_days=?,version=version+1 WHERE id=?",
                utc(next.dueAt()), next.intervalDays(), id);
        events.publishEvent(new StudyActivityRecorded(owner, StudyActivityRecorded.Kind.REVIEW, reviewId, now));
        return new Review(Long.toString(reviewId), Long.toString(id), rating.name(), now, next.dueAt(), next.intervalDays());
    }

    Source source(long owner, String subjectId, String topicId, String documentId) {
        Long subject = optionalId(subjectId);
        Long topic = optionalId(topicId);
        Long document = optionalId(documentId);
        if (topic != null) {
            var subjects = jdbc.queryForList("SELECT subject_id FROM topics WHERE id=?", Long.class, topic);
            if (subjects.isEmpty() || (subject != null && !subject.equals(subjects.getFirst()))) {
                throw invalid("Choose a topic within the selected subject.");
            }
            subject = subjects.getFirst();
        }
        if (subject != null && jdbc.queryForList("SELECT id FROM subjects WHERE id=?", Long.class, subject).isEmpty()) {
            throw invalid("Choose a known subject.");
        }
        if (document != null) requireDocument(owner, document);
        return new Source(subject, topic, document);
    }

    private void requireDocument(long owner, long document) {
        if (jdbc.queryForList("""
                SELECT doc.id FROM documents doc JOIN resources r ON r.id=doc.resource_id
                WHERE doc.id=? AND doc.status='READY' AND (r.user_id=? OR EXISTS(
                    SELECT 1 FROM resource_permissions p WHERE p.resource_id=r.id AND p.user_id=?))
                """, Long.class, document, owner, owner).isEmpty()) throw missing();
    }

    private Deck deck(long owner, long id) {
        return jdbc.query(DECK + ACCESS + " AND d.id=?", DECK_ROW, owner, id)
                .stream().findFirst().orElseThrow(FlashcardService::missing);
    }

    private void lockedDeck(long owner, long id) {
        if (jdbc.queryForList("SELECT d.id FROM flashcard_decks d WHERE " + ACCESS + " AND d.id=? FOR UPDATE",
                Long.class, owner, id).isEmpty()) throw missing();
    }

    private Card card(long owner, long id, boolean lock) {
        return jdbc.query(CARD + ACCESS + " AND c.id=?" + (lock ? " FOR UPDATE" : ""), CARD_ROW, owner, id)
                .stream().findFirst().orElseThrow(FlashcardService::missing);
    }

    private long insertCard(long deck, CardInput card) {
        if (card == null) throw invalid("Card data is required.");
        return insert("""
                INSERT INTO flashcards(deck_id,front,back,due_at) VALUES (?,?,?,UTC_TIMESTAMP(6))
                """, deck, field(card.front(), 1000, "Front"), field(card.back(), 2000, "Back"));
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("Missing generated key.");
        return key.getKey().longValue();
    }

    private static byte[] requestKey(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return ByteBuffer.allocate(16).putLong(parsed.getMostSignificantBits()).putLong(parsed.getLeastSignificantBits()).array();
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid("A UUID review request ID is required.");
        }
    }

    private static FlashcardSchedule.Rating rating(String value) {
        try {
            return FlashcardSchedule.Rating.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid("Choose a valid review rating.");
        }
    }

    static String field(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.strip().length() > limit) {
            throw invalid(name + " must be between 1 and " + limit + " characters.");
        }
        return value.strip();
    }

    static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw invalid("Invalid identifier.");
        }
    }

    private static Long optionalId(String value) { return value == null || value.isBlank() ? null : id(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
    private static long offset(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("Invalid page.");
        return (long) page * size;
    }
    private static <T> Page<T> page(List<T> rows, int page, int size) {
        return new Page<>(rows.stream().limit(size).toList(), page, size, rows.size() > size);
    }
    static ResponseStatusException invalid(String detail) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Flashcard item not found."); }
    private static ResponseStatusException conflict(String detail) { return new ResponseStatusException(HttpStatus.CONFLICT, detail); }

    record Source(Long subject, Long topic, Long document) {}
    public record Deck(String id, String title, String subjectId, String topicId, String documentId, int cardCount,
                       List<Card> cards) {}
    public record Card(String id, String deckId, String front, String back, Instant dueAt, int intervalDays) {}
    public record Review(String id, String cardId, String rating, Instant reviewedAt, Instant dueAt, int intervalDays) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    public record DeckInput(String title, String subjectId, String topicId, String documentId, List<CardInput> cards) {}
    public record CardInput(String front, String back) {}
    public record ReviewInput(String rating, String requestId) {}
}
