package com.studysphere.room.assistant;

import com.studysphere.mentor.provider.MentorProvider;
import com.studysphere.planner.PlannerService;
import com.studysphere.quiz.QuizService;
import com.studysphere.room.RoomService;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
class RoomAssistantService {
    private static final String DISCLOSURE = "Recent room messages and shared notes may be sent to the configured AI provider. Everyone in the room must consent before a summary is generated.";
    private final JdbcTemplate jdbc;
    private final RoomService rooms;
    private final PlannerService planner;
    private final QuizService quizzes;
    private final MentorProvider provider;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    RoomAssistantService(JdbcTemplate jdbc, RoomService rooms, PlannerService planner, QuizService quizzes,
                         MentorProvider provider, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.rooms = rooms;
        this.planner = planner;
        this.quizzes = quizzes;
        this.provider = provider;
        this.json = json;
        transactions = new TransactionTemplate(manager);
    }

    Assistant assistant(long user, long room) {
        return transactions.execute(status -> {
            rooms.lockRoom(room);
            rooms.requireMember(user, room);
            boolean consented = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM room_assistant_consents WHERE room_id=? AND user_id=?)
                    """, Boolean.class, room, user);
            var summaries = jdbc.query("""
                    SELECT id,body FROM room_ai_summaries WHERE room_id=? ORDER BY id DESC LIMIT 30
                    """, (rs, index) -> new Summary(rs.getString(1), rs.getString(2), List.of()), room);
            return new Assistant(consented, allConsented(room), summaries.stream()
                    .map(summary -> new Summary(summary.id(), summary.text(),
                            proposals(user, Long.parseLong(summary.id())))).toList(), DISCLOSURE);
        });
    }

    void consent(long user, long room, Boolean consented) {
        if (consented == null) throw invalid("Choose whether you consent.");
        transactions.executeWithoutResult(status -> {
            rooms.lockRoom(room);
            rooms.requireMember(user, room);
            if (consented) {
                jdbc.update("INSERT IGNORE INTO room_assistant_consents(room_id,user_id) VALUES(?,?)", room, user);
            } else {
                jdbc.update("DELETE FROM room_assistant_consents WHERE room_id=? AND user_id=?", room, user);
            }
        });
    }

    List<Note> notes(long user, long room) {
        return transactions.execute(status -> {
            rooms.lockRoom(room);
            rooms.requireMember(user, room);
            return jdbc.query("SELECT id,body,author_id FROM room_shared_notes WHERE room_id=? ORDER BY id DESC LIMIT 100",
                    (rs, i) -> new Note(rs.getString(1), rs.getString(2), rs.getString(3)), room);
        });
    }

    Note addNote(long user, long room, String text) {
        String body = text(text, 4000);
        return transactions.execute(status -> {
            rooms.lockRoom(room);
            rooms.requireMember(user, room);
            long id = insert("INSERT INTO room_shared_notes(room_id,author_id,body) VALUES(?,?,?)", room, user, body);
            return new Note(Long.toString(id), body, Long.toString(user));
        });
    }

    void removeNote(long user, long room, long note) {
        transactions.executeWithoutResult(status -> {
            rooms.lockRoom(room);
            rooms.requireMember(user, room);
            var authors = jdbc.queryForList("SELECT author_id FROM room_shared_notes WHERE id=? AND room_id=?", Long.class, note, room);
            if (authors.isEmpty()) throw missing();
            if (authors.getFirst() != user) rooms.requireModerator(user, room);
            jdbc.update("DELETE FROM room_shared_notes WHERE id=? AND room_id=?", note, room);
        });
    }

    Summary summarize(long user, long room, Boolean disclosed) {
        if (!Boolean.TRUE.equals(disclosed)) throw invalid("Acknowledge the AI disclosure first.");
        String input = transactions.execute(status -> {
            rooms.lockRoom(room);
            requireGeneration(user, room);
            var notes = context(room, "room_shared_notes", "author_id", 5);
            var messages = context(room, "room_messages", "sender_id", 10);
            if (notes.isEmpty() && messages.isEmpty()) throw invalid("Add room content before requesting a summary.");
            return json.writeValueAsString(Map.of("notes", notes, "messages", messages));
        });
        if (!provider.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure an AI provider first.");

        DraftSummary draft;
        try {
            String response = provider.stream("ROOM_ASSISTANT", List.of(new MentorProvider.Turn("user", input)))
                    .reduceWith(StringBuilder::new, (builder, part) -> {
                        if (builder.length() + part.length() > 12000) throw new IllegalArgumentException("Output too large");
                        return builder.append(part);
                    }).map(StringBuilder::toString).block(Duration.ofSeconds(40));
            draft = validate(json.readValue(response, DraftSummary.class));
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The room assistant could not generate a valid summary.");
        }

        return transactions.execute(status -> {
            rooms.lockRoom(room);
            requireGeneration(user, room);
            long id = insert("INSERT INTO room_ai_summaries(room_id,body) VALUES(?,?)", room, draft.text());
            draft.proposals().forEach(proposal ->
                    insert("INSERT INTO room_ai_proposals(summary_id,draft) VALUES(?,?)", id, json.writeValueAsString(proposal)));
            return new Summary(Long.toString(id), draft.text(), proposals(user, id));
        });
    }

    private List<String> context(long room, String table, String authorColumn, int limit) {
        return jdbc.queryForList("""
                SELECT LEFT(source.body,600) FROM %s source
                JOIN room_assistant_consents c ON c.room_id=source.room_id AND c.user_id=source.%s
                WHERE source.room_id=? ORDER BY source.id DESC LIMIT ?
                """.formatted(table, authorColumn), String.class, room, limit).reversed();
    }

    Proposal approve(long user, long room, long id) {
        return transactions.execute(status -> {
            rooms.lockRoom(room);
            rooms.requireModerator(user, room);
            var found = jdbc.query("""
                    SELECT p.id,p.draft,p.approved,p.approver_id,p.result_id FROM room_ai_proposals p
                    JOIN room_ai_summaries s ON s.id=p.summary_id WHERE s.room_id=? AND p.id=?
                    """, (rs, index) -> new StoredProposal(rs.getString(1), json.readValue(rs.getString(2), DraftProposal.class),
                    rs.getBoolean(3), rs.getLong(4), rs.getString(5)), room, id);
            if (found.isEmpty()) throw missing();
            var stored = found.getFirst();
            var draft = stored.draft();
            if (stored.approved()) return view(user, stored);
            String result = switch (draft.kind()) {
                case "TASK" -> planner.createTask(user,
                        new PlannerService.TaskInput(null, null, draft.title(), null, null, null, null, null, null)).id();
                case "QUIZ" -> quizzes.createRoomQuestion(user, draft.title(), draft.prompt(), draft.options(),
                        draft.correctIndex(), draft.explanation());
                default -> throw new IllegalStateException("Unknown proposal kind");
            };
            jdbc.update("UPDATE room_ai_proposals SET approved=TRUE,approver_id=?,result_id=? WHERE id=?",
                    user, Long.parseLong(result), id);
            return view(user, new StoredProposal(stored.id(), draft, true, user, result));
        });
    }

    private boolean allConsented(long room) {
        return jdbc.queryForObject("""
                SELECT NOT EXISTS(SELECT 1 FROM room_members m
                    LEFT JOIN room_assistant_consents c ON c.room_id=m.room_id AND c.user_id=m.user_id
                    WHERE m.room_id=? AND c.user_id IS NULL)
                """, Boolean.class, room);
    }

    private void requireGeneration(long user, long room) {
        rooms.requireModerator(user, room);
        if (!allConsented(room)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Everyone in the room must consent.");
    }

    private List<Proposal> proposals(long user, long summary) {
        return jdbc.query("""
                SELECT id,draft,approved,approver_id,result_id FROM room_ai_proposals
                WHERE summary_id=? ORDER BY id
                """, (rs, index) -> view(user, new StoredProposal(rs.getString(1),
                json.readValue(rs.getString(2), DraftProposal.class), rs.getBoolean(3), rs.getLong(4), rs.getString(5))), summary);
    }

    private static Proposal view(long user, StoredProposal stored) {
        var draft = stored.draft();
        return new Proposal(stored.id(), draft.kind(), draft.title(), draft.prompt(), draft.options(),
                stored.approved(), user == stored.approver() ? stored.result() : null);
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static DraftSummary validate(DraftSummary draft) {
        if (draft == null || draft.proposals() == null || draft.proposals().size() > 6) throw invalid("Invalid AI proposals.");
        return new DraftSummary(text(draft.text(), 1800), draft.proposals().stream().map(proposal -> {
            if (proposal == null || proposal.kind() == null) throw invalid("Invalid AI proposal.");
            String title = text(proposal.title(), 150);
            return switch (proposal.kind()) {
                case "TASK" -> new DraftProposal("TASK", title, null, List.of(), null, null);
                case "QUIZ" -> {
                    if (proposal.options() == null || proposal.options().size() < 2 || proposal.options().size() > 6
                            || proposal.correctIndex() == null || proposal.correctIndex() < 0
                            || proposal.correctIndex() >= proposal.options().size()) throw invalid("Invalid AI quiz.");
                    var options = proposal.options().stream().map(option -> text(option, 350)).toList();
                    if (options.stream().map(value -> value.toLowerCase(java.util.Locale.ROOT)).distinct().count() != options.size()) {
                        throw invalid("Invalid AI quiz.");
                    }
                    yield new DraftProposal("QUIZ", title, text(proposal.prompt(), 700), options,
                            proposal.correctIndex(), text(proposal.explanation(), 1200));
                }
                default -> throw invalid("Invalid AI proposal kind.");
            };
        }).toList());
    }

    private static String text(String input, int max) {
        if (input == null || input.isBlank() || input.length() > max) throw invalid("Invalid text.");
        return input.strip();
    }
    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Room item not found.");
    }

    record Assistant(boolean consented, boolean allConsented, List<Summary> summaries, String disclosure) {}
    record Summary(String id, String text, List<Proposal> proposals) {}
    record Note(String id, String text, String authorId) {}
    record Proposal(String id, String kind, String title, String prompt, List<String> options, boolean approved, String resultId) {}
    private record StoredProposal(String id, DraftProposal draft, boolean approved, long approver, String result) {}
    private record DraftSummary(String text, List<DraftProposal> proposals) {}
    private record DraftProposal(String kind, String title, String prompt, List<String> options, Integer correctIndex, String explanation) {}
}
