package com.studysphere.planner;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import com.studysphere.engagement.StudyActivityRecorded;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlannerService {
    private static final int MAX_PARENT_DEPTH = 8;
    private static final RowMapper<Plan> PLAN = (rs, row) -> new Plan(
            rs.getString("id"), rs.getString("title"), rs.getString("subject_id"),
            rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), rs.getInt("version"));
    private static final RowMapper<Task> TASK = (rs, row) -> new Task(
            rs.getString("id"), rs.getString("plan_id"), rs.getString("parent_id"), rs.getString("title"),
            rs.getString("priority"), rs.getString("state"), utc(rs, "due_at"),
            rs.getString("recurrence_rule"), rs.getString("recurrence_zone"),
            rs.getString("occurrence_origin_id"), utc(rs, "occurrence_at"), rs.getInt("version"));
    private static final RowMapper<Reminder> REMINDER = (rs, row) -> new Reminder(
            rs.getString("id"), rs.getString("task_id"), utc(rs, "scheduled_at"), rs.getString("state"));
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    PlannerService(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public Page<Plan> plans(long owner, int page, int size) {
        int offset = offset(page, size);
        return page(jdbc.query("SELECT * FROM study_plans WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                PLAN, owner, size + 1, offset), page, size);
    }

    public Plan plan(long owner, long id) {
        return owned("study_plans", PLAN, owner, id);
    }

    @Transactional
    public Plan createPlan(long owner, PlanInput input) {
        lockOwner(owner);
        var fields = planFields(input, null);
        long id = insert("""
                INSERT INTO study_plans (user_id,title,subject_id,start_date,end_date) VALUES (?,?,?,?,?)
                """, owner, fields.title(), fields.subjectId(), fields.startDate(), fields.endDate());
        return plan(owner, id);
    }

    @Transactional
    public Plan updatePlan(long owner, long id, PlanInput input) {
        lockOwner(owner);
        Plan current = plan(owner, id);
        requireVersion(input.version(), current.version());
        var fields = planFields(input, current);
        jdbc.update("""
                UPDATE study_plans SET title=?,subject_id=?,start_date=?,end_date=?,version=version+1
                WHERE user_id=? AND id=?
                """, fields.title(), fields.subjectId(), fields.startDate(), fields.endDate(), owner, id);
        return plan(owner, id);
    }

    @Transactional
    public void deletePlan(long owner, long id) {
        lockOwner(owner);
        plan(owner, id);
        jdbc.update("UPDATE study_tasks SET plan_id=NULL,version=version+1 WHERE user_id=? AND plan_id=?", owner, id);
        jdbc.update("DELETE FROM study_plans WHERE user_id=? AND id=?", owner, id);
    }

    public Page<Task> tasks(long owner, String from, String to, String state, int page, int size) {
        int offset = offset(page, size);
        Instant start = instant(from);
        Instant end = instant(to);
        if (start != null && end != null && !start.isBefore(end)) {
            throw invalid("The start must be before the end.");
        }
        var sql = new StringBuilder("SELECT * FROM study_tasks WHERE user_id=?");
        var args = new ArrayList<>();
        args.add(owner);
        if (start != null) {
            sql.append(" AND due_at>=?");
            args.add(utc(start));
        }
        if (end != null) {
            sql.append(" AND due_at<?");
            args.add(utc(end));
        }
        if (state != null) {
            sql.append(" AND state=?");
            args.add(taskState(state));
        }
        sql.append(" ORDER BY due_at IS NULL, due_at, id LIMIT ? OFFSET ?");
        args.add(size + 1);
        args.add(offset);
        return page(jdbc.query(sql.toString(), TASK, args.toArray()), page, size);
    }

    public List<Task> todaysTasks(long owner, Instant from, Instant to) {
        return tasks(owner, from.toString(), to.toString(), null, 0, 20).items();
    }

    public Task task(long owner, long id) {
        return owned("study_tasks", TASK, owner, id);
    }

    @Transactional
    public Task createTask(long owner, TaskInput input) {
        lockOwner(owner);
        var fields = taskFields(owner, 0, input, null);
        if (!fields.state().equals("OPEN")) {
            throw invalid("A new task must be open.");
        }
        return task(owner, insertTask(owner, fields, null, null, fields.dueAt()));
    }

    @Transactional
    public Task updateTask(long owner, long id, TaskInput input) {
        lockOwner(owner);
        Task current = task(owner, id);
        if (current.state().equals("COMPLETED") && "COMPLETED".equals(input.state())
                && input.isCompletionOnly()) {
            return current;
        }
        requireVersion(input.version(), current.version());
        var fields = taskFields(owner, id, input, current);
        if (current.state().equals("COMPLETED") && !fields.state().equals("COMPLETED")) {
            throw invalid("Completed tasks cannot be reopened.");
        }
        Instant anchor = anchor(owner, id);
        if (current.occurrenceOriginId() == null && (input.dueAt() != null
                || input.recurrenceZone() != null || input.recurrenceRule() != null)) {
            anchor = fields.dueAt();
        }
        jdbc.update("""
                UPDATE study_tasks SET plan_id=?,parent_id=?,title=?,priority=?,state=?,due_at=?,
                    recurrence_rule=?,recurrence_zone=?,series_anchor_at=?,version=version+1,
                    completed_at=CASE WHEN ?='COMPLETED' THEN COALESCE(completed_at,UTC_TIMESTAMP(6)) ELSE NULL END
                WHERE user_id=? AND id=?
                """, fields.planId(), fields.parentId(), fields.title(), fields.priority(), fields.state(),
                utc(fields.dueAt()), fields.rule(), fields.zone(), utc(anchor), fields.state(), owner, id);
        if (current.state().equals("OPEN") && fields.state().equals("COMPLETED")) {
            if (fields.rule() != null) scheduleNext(owner, id, current, fields, anchor);
            events.publishEvent(new StudyActivityRecorded(owner, StudyActivityRecorded.Kind.TASK, id, Instant.now()));
        }
        return task(owner, id);
    }

    @Transactional
    public void deleteTask(long owner, long id) {
        lockOwner(owner);
        task(owner, id);
        jdbc.update("UPDATE study_tasks SET parent_id=NULL,version=version+1 WHERE user_id=? AND parent_id=?", owner, id);
        jdbc.update("DELETE FROM study_tasks WHERE user_id=? AND id=?", owner, id);
    }

    public Page<Reminder> reminders(long owner, long taskId, int page, int size) {
        int offset = offset(page, size);
        task(owner, taskId);
        return page(jdbc.query("""
                SELECT r.* FROM task_reminders r JOIN study_tasks t ON t.id=r.task_id
                WHERE t.user_id=? AND r.task_id=? ORDER BY r.scheduled_at, r.id LIMIT ? OFFSET ?
                """, REMINDER, owner, taskId, size + 1, offset), page, size);
    }

    @Transactional
    public Reminder createReminder(long owner, long taskId, String schedule) {
        lockOwner(owner);
        Task task = task(owner, taskId);
        Instant scheduledAt = instant(schedule);
        if (scheduledAt == null || !scheduledAt.isAfter(Instant.now()) ||
                (task.dueAt() != null && scheduledAt.isAfter(task.dueAt())) || !task.state().equals("OPEN")) {
            throw invalid("Schedule a future reminder for an open task at or before its due date.");
        }
        try {
            long id = insert("INSERT INTO task_reminders(task_id,scheduled_at) VALUES (?,?)", taskId, utc(scheduledAt));
            return jdbc.queryForObject("SELECT * FROM task_reminders WHERE id=?", REMINDER, id);
        } catch (DuplicateKeyException duplicate) {
            throw failure(409, "DUPLICATE_REMINDER", "A reminder already exists for that time.");
        }
    }

    @Transactional
    public void deleteReminder(long owner, long taskId, long reminderId) {
        lockOwner(owner);
        task(owner, taskId);
        if (jdbc.update("DELETE FROM task_reminders WHERE task_id=? AND id=?", taskId, reminderId) == 0) {
            throw notFound();
        }
    }

    private void scheduleNext(long owner, long id, Task current, TaskFields fields, Instant anchor) {
        long origin = current.occurrenceOriginId() == null ? id : Long.parseLong(current.occurrenceOriginId());
        Instant reference = current.occurrenceAt() == null ? fields.dueAt() : current.occurrenceAt();
        Instant next = nextOccurrence(anchor == null ? reference : anchor, reference, fields.rule(), ZoneId.of(fields.zone()));
        var following = new TaskFields(fields.planId(), fields.parentId(), fields.title(), fields.priority(), "OPEN",
                next, fields.rule(), fields.zone());
        insertTask(owner, following, origin, next, anchor);
    }

    static Instant nextOccurrence(Instant anchor, Instant reference, String rule, ZoneId zone) {
        LocalDate date = reference.atZone(zone).toLocalDate();
        LocalDate nextDate = "WEEKLY".equals(rule) ? date.plusWeeks(1) : date.plusDays(1);
        // atZone shifts a gap forward and chooses the earlier offset during an overlap.
        return nextDate.atTime(anchor.atZone(zone).toLocalTime()).atZone(zone).toInstant();
    }

    private long insertTask(long owner, TaskFields fields, Long origin, Instant occurrence, Instant anchor) {
        return insert("""
                INSERT INTO study_tasks (user_id,plan_id,parent_id,title,priority,state,due_at,recurrence_rule,
                    recurrence_zone,occurrence_origin_id,occurrence_at,series_anchor_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, owner, fields.planId(), fields.parentId(), fields.title(), fields.priority(), fields.state(),
                utc(fields.dueAt()), fields.rule(), fields.zone(), origin, utc(occurrence), utc(anchor));
    }

    private Instant anchor(long owner, long id) {
        return jdbc.queryForObject("SELECT series_anchor_at FROM study_tasks WHERE user_id=? AND id=?",
                (rs, row) -> utc(rs, "series_anchor_at"), owner, id);
    }

    private TaskFields taskFields(long owner, long id, TaskInput input, Task current) {
        Long planId = id(patch(input.planId(), current == null ? null : current.planId()));
        Long parentId = id(patch(input.parentId(), current == null ? null : current.parentId()));
        if (planId != null) plan(owner, planId);
        checkParent(owner, id, parentId);
        String title = title(patch(input.title(), current == null ? null : current.title()));
        String priority = patch(input.priority(), current == null ? "MEDIUM" : current.priority());
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(priority == null ? "" : priority)) {
            throw invalid("Priority must be LOW, MEDIUM, or HIGH.");
        }
        String state = taskState(patch(input.state(), current == null ? "OPEN" : current.state()));
        Instant due = input.dueAt() == null && current != null ? current.dueAt() : instant(input.dueAt());
        String rule = patch(input.recurrenceRule(), current == null ? null : current.recurrenceRule());
        String zone = patch(input.recurrenceZone(), current == null ? null : current.recurrenceZone());
        if (rule != null) {
            if (!Set.of("DAILY", "WEEKLY").contains(rule) || due == null ||
                    zone == null || zone.length() > 80 || !ZoneId.getAvailableZoneIds().contains(zone)) {
                throw invalid("A recurring task needs a daily or weekly rule, due time, and IANA timezone.");
            }
        } else {
            zone = null;
        }
        if (current != null && current.occurrenceOriginId() != null && (input.recurrenceRule() != null
                || input.recurrenceZone() != null) &&
                (!java.util.Objects.equals(rule, current.recurrenceRule())
                || !java.util.Objects.equals(zone, current.recurrenceZone()))) {
            if (rule != null) throw invalid("Create a new recurring task to change the recurrence of a generated task.");
        }
        return new TaskFields(planId, parentId, title, priority, state, due, rule, zone);
    }

    private PlanFields planFields(PlanInput input, Plan current) {
        Long subjectId = id(patch(input.subjectId(), current == null ? null : current.subjectId()));
        if (subjectId != null && jdbc.queryForList("SELECT id FROM subjects WHERE id=?", Long.class, subjectId).isEmpty()) {
            throw invalid("Choose an existing subject.");
        }
        String title = title(patch(input.title(), current == null ? null : current.title()));
        LocalDate start = input.startDate() == null && current != null ? current.startDate() : date(input.startDate());
        LocalDate end = input.endDate() == null && current != null ? current.endDate() : date(input.endDate());
        if (start != null && end != null && start.isAfter(end)) throw invalid("The plan start must be on or before its end.");
        return new PlanFields(title, subjectId, start, end);
    }

    private void checkParent(long owner, long id, Long parent) {
        int depth = 0;
        while (parent != null) {
            if (parent == id || ++depth > MAX_PARENT_DEPTH) throw invalid("This parent would create an invalid task hierarchy.");
            parent = id(task(owner, parent).parentId());
        }
        if (id != 0 && depth != 0) {
            Integer childDepth = jdbc.queryForObject("""
                    WITH RECURSIVE descendants (id, level_number) AS (
                        SELECT id, 0 FROM study_tasks WHERE user_id=? AND id=?
                        UNION ALL
                        SELECT child.id, parent.level_number+1 FROM study_tasks child
                        JOIN descendants parent ON child.parent_id=parent.id WHERE child.user_id=?
                    ) SELECT MAX(level_number) FROM descendants
                    """, Integer.class, owner, id, owner);
            if (childDepth != null && depth + childDepth > MAX_PARENT_DEPTH) {
                throw invalid("This parent would make the task hierarchy too deep.");
            }
        }
    }

    private <T> T owned(String table, RowMapper<T> mapper, long owner, long id) {
        return jdbc.query("SELECT * FROM " + table + " WHERE user_id=? AND id=?", mapper, owner, id)
                .stream().findFirst().orElseThrow(PlannerService::notFound);
    }

    private void lockOwner(long owner) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, owner).isEmpty()) {
            throw notFound();
        }
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int position = 0; position < values.length; position++) {
                statement.setObject(position + 1, values[position]);
            }
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static void requireVersion(Integer supplied, int current) {
        if (supplied == null) throw invalid("A version is required to edit this record.");
        if (supplied != current) throw failure(409, "VERSION_CONFLICT", "This record changed. Reload it and retry.");
    }

    private static String title(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw invalid("Enter a title of 1 to 160 characters.");
        }
        return value.strip();
    }

    private static String patch(String candidate, String previous) {
        return candidate == null ? previous : candidate.isBlank() ? null : candidate.strip();
    }

    private static String taskState(String state) {
        if (!Set.of("OPEN", "COMPLETED").contains(state == null ? "" : state)) {
            throw invalid("State must be OPEN or COMPLETED.");
        }
        return state;
    }

    static Long id(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long id = Long.parseLong(value);
            if (id > 0 && Long.toString(id).equals(value)) return id;
        } catch (NumberFormatException ignored) {
            // Convert malformed IDs to the shared problem response below.
        }
        throw invalid("Use a positive decimal record ID.");
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            Instant time = Instant.parse(value);
            utc(time);
            return time.truncatedTo(ChronoUnit.MICROS);
        } catch (java.time.DateTimeException e) {
            throw invalid("Enter an ISO 8601 timestamp.");
        }
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate result = LocalDate.parse(value);
            if (result.getYear() >= 1000 && result.getYear() <= 9999) return result;
        } catch (java.time.DateTimeException ignored) {
            // Convert malformed dates to the shared problem response below.
        }
        throw invalid("Enter an ISO calendar date.");
    }

    private static LocalDateTime utc(Instant instant) {
        if (instant == null) return null;
        LocalDateTime value = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        if (value.getYear() < 1000 || value.getYear() > 9999) {
            throw invalid("Enter an ISO timestamp in the supported year range.");
        }
        return value;
    }

    private static Instant utc(ResultSet rs, String name) throws SQLException {
        LocalDateTime value = rs.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static int offset(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw invalid("Use page 0 to 10000 and size 1 to 100.");
        }
        return page * size;
    }

    private static <T> Page<T> page(List<T> values, int page, int size) {
        return new Page<>(List.copyOf(values.subList(0, Math.min(size, values.size()))), page, size, values.size() > size);
    }

    static PlannerFailure invalid(String detail) { return failure(400, "INVALID_PLANNER_REQUEST", detail); }
    static PlannerFailure notFound() { return failure(404, "PLANNER_RECORD_NOT_FOUND", "This record was not found."); }
    private static PlannerFailure failure(int status, String code, String detail) {
        return new PlannerFailure(status, code, detail);
    }

    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    public record Plan(String id, String title, String subjectId, LocalDate startDate, LocalDate endDate, int version) {}
    public record PlanInput(String title, String subjectId, String startDate, String endDate, Integer version) {}
    public record Task(String id, String planId, String parentId, String title, String priority, String state,
                       Instant dueAt, String recurrenceRule, String recurrenceZone, String occurrenceOriginId,
                       Instant occurrenceAt, int version) {}
    public record TaskInput(String planId, String parentId, String title, String priority, String state,
                            String dueAt, String recurrenceRule, String recurrenceZone, Integer version) {
        boolean isCompletionOnly() {
            return planId == null && parentId == null && title == null && priority == null && dueAt == null
                    && recurrenceRule == null && recurrenceZone == null;
        }
    }
    public record Reminder(String id, String taskId, Instant scheduledAt, String state) {}
    private record PlanFields(String title, Long subjectId, LocalDate startDate, LocalDate endDate) {}
    private record TaskFields(Long planId, Long parentId, String title, String priority, String state,
                              Instant dueAt, String rule, String zone) {}
}
