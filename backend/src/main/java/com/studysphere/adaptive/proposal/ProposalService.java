package com.studysphere.adaptive.proposal;

import com.studysphere.adaptive.MasteryService;
import com.studysphere.mentor.provider.MentorProvider;
import com.studysphere.mentor.provider.ModelJson;
import com.studysphere.planner.PlannerService;
import com.studysphere.planner.PlannerService.PlanInput;
import com.studysphere.planner.PlannerService.TaskInput;
import com.studysphere.productivity.ProductivityService;
import com.studysphere.profile.ProfileService;
import com.studysphere.profile.ProfileService.Slot;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProposalService {
    private static final TypeReference<List<DraftTask>> TASKS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ProfileService profile;
    private final PlannerService planner;
    private final ProductivityService productivity;
    private final MasteryService mastery;
    private final MentorProvider provider;
    private final ObjectMapper json;

    ProposalService(JdbcTemplate jdbc, ProfileService profile, PlannerService planner,
            ProductivityService productivity, MasteryService mastery, MentorProvider provider, ObjectMapper json) {
        this.jdbc = jdbc;
        this.profile = profile;
        this.planner = planner;
        this.productivity = productivity;
        this.mastery = mastery;
        this.provider = provider;
        this.json = json;
    }

    public Page list(long owner, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw invalid("Choose a valid page and size.");
        }
        var items = jdbc.query("""
                SELECT * FROM study_plan_proposals WHERE user_id=? ORDER BY id DESC LIMIT ? OFFSET ?
                """, (row, index) -> stored(row).view(), owner, size + 1, page * size);
        return new Page(items.stream().limit(size).toList(), page, size, items.size() > size);
    }

    public Preview get(long owner, long id) {
        return load(owner, id, false).view();
    }

    public Preview create(long owner, Input input) {
        ZoneId zone = zone(owner);
        LocalDate today = LocalDate.now(zone);
        if (input == null || input.minutesPerDay() == null
                || input.minutesPerDay() < 5 || input.minutesPerDay() > 480) {
            throw invalid("Choose a daily budget from 5 to 480 minutes.");
        }
        String title = title(input.title());
        LocalDate start = date(input.startDate());
        LocalDate end = date(input.endDate());
        if (start.isBefore(today) || start.isAfter(today.plusDays(90)) || end.isBefore(start)
                || ChronoUnit.DAYS.between(start, end) >= 14) {
            throw invalid("Choose up to 14 days, starting today or in the next 90 days.");
        }
        if (!provider.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No study AI provider is configured.");
        }
        var availability = profile.availability(owner).slots();
        var exams = productivity.upcomingExams(owner, today).stream()
                .map(exam -> Map.of("id", exam.id(), "title", exam.title(), "date", exam.examDate().toString()))
                .toList();
        var weakTopics = mastery.topics(owner, 0, 30).items().stream()
                .filter(topic -> "WEAK".equals(String.valueOf(topic.state())))
                .map(topic -> Map.of("name", topic.topicName(), "subject", topic.subjectName())).toList();
        var pending = planner.tasks(owner, null, null, "OPEN", 0, 15).items().stream()
                .map(task -> task.title()).toList();
        Map<String, Object> context = Map.of("title", title, "startDate", start.toString(),
                "endDate", end.toString(), "minutesPerDay", input.minutesPerDay(), "timeZone", zone.getId(),
                "availability", availability, "upcomingExams", exams, "weakTopics", weakTopics,
                "pendingTasks", pending);
        List<DraftTask> tasks;
        try {
            String output = provider.stream("PLAN_PROPOSAL",
                    List.of(new MentorProvider.Turn("USER", json.writeValueAsString(context))))
                    .reduceWith(StringBuilder::new, (builder, fragment) -> {
                        if (builder.length() + fragment.length() > 16000) {
                            throw new IllegalArgumentException("Proposal output limit exceeded.");
                        }
                        return builder.append(fragment);
                    }).map(StringBuilder::toString).block(Duration.ofSeconds(40));
            tasks = schedule(owner, zone, start, end, input.minutesPerDay(), availability, parse(output));
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The study provider returned an invalid or unavailable proposal.");
        }
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO study_plan_proposals
                      (user_id,title,start_date,end_date,time_zone,minutes_per_day,schedule,created_at)
                    VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, owner);
            statement.setString(2, title);
            statement.setObject(3, start);
            statement.setObject(4, end);
            statement.setString(5, zone.getId());
            statement.setInt(6, input.minutesPerDay());
            statement.setString(7, json.writeValueAsString(tasks));
            return statement;
        }, key);
        return get(owner, key.getKey().longValue());
    }

    @Transactional
    public Preview approve(long owner, long id) {
        var owners = jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, owner);
        if (owners.isEmpty()) throw missing();
        var saved = load(owner, id, true);
        Preview preview = saved.view();
        if ("APPROVED".equals(preview.status())) return preview;
        ZoneId zone = ZoneId.of(saved.timeZone());
        var availability = profile.availability(owner).slots();
        Instant now = Instant.now();
        if (!zone.equals(zone(owner)) || preview.tasks().stream().anyMatch(task -> {
            Instant begin = task.dueAt().minus(Duration.ofMinutes(task.minutes()));
            var windows = windows(task.dueAt().atZone(zone).toLocalDate(), zone, availability);
            return !begin.isAfter(now) || windows.stream().noneMatch(window ->
                    !begin.isBefore(window.start()) && !task.dueAt().isAfter(window.end()))
                    || missesExam(owner, task.examId(), task.dueAt());
        })) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule changed. Generate a new preview.");
        }
        String plan = planner.createPlan(owner, new PlanInput(preview.title(), null,
                preview.startDate().toString(), preview.endDate().toString(), null)).id();
        for (DraftTask task : preview.tasks()) {
            planner.createTask(owner, new TaskInput(plan, null, task.title(), "MEDIUM", "OPEN",
                    task.dueAt().toString(), null, null, null));
        }
        jdbc.update("UPDATE study_plan_proposals SET status='APPROVED',approved_plan_id=? WHERE id=? AND user_id=?",
                Long.parseLong(plan), id, owner);
        return get(owner, id);
    }

    private List<DraftTask> schedule(long owner, ZoneId zone, LocalDate start, LocalDate end, int dailyBudget,
            List<Slot> availability, List<SuggestedTask> suggested) {
        Instant now = Instant.now();
        var tasks = new ArrayList<DraftTask>();
        for (var entry : suggested.stream().collect(java.util.stream.Collectors.groupingBy(
                SuggestedTask::date, java.util.TreeMap::new, java.util.stream.Collectors.toList())).entrySet()) {
            LocalDate day = entry.getKey();
            var items = entry.getValue();
            if (day.isBefore(start) || day.isAfter(end) || day.isBefore(now.atZone(zone).toLocalDate())
                    || items.stream().mapToInt(SuggestedTask::minutes).sum() > dailyBudget) {
                throw invalid("Proposed tasks exceed the date range or daily budget.");
            }
            var windows = windows(day, zone, availability);
            int slot = 0;
            Instant soonest = now.truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));
            Instant cursor = windows.isEmpty() ? soonest : later(windows.getFirst().start(), soonest);
            for (SuggestedTask item : items) {
                while (slot < windows.size()
                        && cursor.plus(Duration.ofMinutes(item.minutes())).isAfter(windows.get(slot).end())) {
                    slot++;
                    if (slot < windows.size()) cursor = later(windows.get(slot).start(), soonest);
                }
                if (slot == windows.size()) throw invalid("Proposed tasks exceed your availability.");
                cursor = cursor.plus(Duration.ofMinutes(item.minutes()));
                if (!cursor.isAfter(now)) throw invalid("Proposed tasks must finish in the future.");
                if (missesExam(owner, item.examId(), cursor)) throw invalid("Proposed task misses its exam deadline.");
                tasks.add(new DraftTask(item.title(), cursor, item.minutes(), item.examId()));
            }
        }
        return List.copyOf(tasks);
    }

    private boolean missesExam(long owner, String id, Instant dueAt) {
        if (id == null) return false;
        var exam = productivity.exam(owner, id);
        return dueAt.isAfter(exam.examDate().atStartOfDay(ZoneId.of(exam.timeZone())).toInstant());
    }

    private static Instant later(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static List<Window> windows(LocalDate day, ZoneId zone, List<Slot> availability) {
        if (availability.isEmpty()) {
            return List.of(new Window(day.atTime(9, 0).atZone(zone).toInstant(),
                    day.atTime(21, 0).atZone(zone).toInstant()));
        }
        return availability.stream().filter(slot -> DayOfWeek.valueOf(slot.day()) == day.getDayOfWeek())
                .map(slot -> new Window(ZonedDateTime.of(day, LocalTime.parse(slot.start()), zone).toInstant(),
                        ZonedDateTime.of(day, LocalTime.parse(slot.end()), zone).toInstant()))
                .sorted(Comparator.comparing(Window::start)).toList();
    }

    private List<SuggestedTask> parse(String output) {
        var root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(ModelJson.unwrap(output));
        var tasks = root == null ? null : root.get("tasks");
        if (root == null || !root.isObject() || root.size() != 1 || tasks == null || !tasks.isArray()
                || tasks.size() < 1 || tasks.size() > 40) throw invalid("Malformed task proposal.");
        var result = new ArrayList<SuggestedTask>();
        for (var task : tasks) {
            var minutes = task.path("minutes");
            var exam = task.get("examId");
            if (!task.isObject() || !task.path("title").isString() || !task.path("date").isString()
                    || !minutes.isIntegralNumber() || !minutes.canConvertToInt()
                    || minutes.intValue() < 5 || minutes.intValue() > 480
                    || (exam != null && !exam.isNull() && !exam.isString())
                    || task.size() != (exam == null ? 3 : 4)) throw invalid("Malformed proposed task.");
            result.add(new SuggestedTask(title(task.path("title").asText()), date(task.path("date").asText()),
                    minutes.intValue(), exam == null || exam.isNull() ? null : exam.asText()));
        }
        return List.copyOf(result);
    }

    private Stored load(long owner, long id, boolean lock) {
        return jdbc.query("SELECT * FROM study_plan_proposals WHERE user_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> stored(row), owner, id).stream().findFirst().orElseThrow(ProposalService::missing);
    }

    private Stored stored(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Stored(row.getString("time_zone"), new Preview(row.getString("id"), row.getString("title"),
                row.getObject("start_date", LocalDate.class), row.getObject("end_date", LocalDate.class),
                row.getInt("minutes_per_day"), row.getString("status"), json.readValue(row.getString("schedule"), TASKS),
                row.getString("approved_plan_id")));
    }

    private ZoneId zone(long owner) {
        String value = profile.preferences(owner).timeZone();
        return value == null ? ZoneOffset.UTC : ZoneId.of(value);
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException failure) {
            throw invalid("Supply a date as YYYY-MM-DD.");
        }
    }

    private static String title(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty() || value.length() > 160) throw invalid("Titles must contain 1–160 characters.");
        return value;
    }

    private static ResponseStatusException invalid(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan proposal not found.");
    }

    public record Input(String title, String startDate, String endDate, Integer minutesPerDay) {}
    public record DraftTask(String title, Instant dueAt, int minutes, String examId) {}
    public record Preview(String id, String title, LocalDate startDate, LocalDate endDate, int minutesPerDay,
                          String status, List<DraftTask> tasks, String planId) {}
    public record Page(List<Preview> items, int page, int size, boolean hasNext) {}
    private record Window(Instant start, Instant end) {}
    private record SuggestedTask(String title, LocalDate date, int minutes, String examId) {}
    private record Stored(String timeZone, Preview view) {}
}
