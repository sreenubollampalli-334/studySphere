package com.studysphere.profile;

import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static com.studysphere.profile.ProfileService.*;

@Repository
class ProfileStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final RowMapper<CatalogItem> CATALOG = (row, i) ->
            new CatalogItem(row.getString("id"), row.getString("name"));
    private static final RowMapper<Goal> GOAL = (row, i) ->
            new Goal(row.getString("id"), row.getString("subject_id"), row.getString("description"),
                    row.getString("target_date"), row.getString("status"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    ProfileStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean lockOwner(long owner) {
        return !jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, owner).isEmpty();
    }

    Optional<Profile> profile(long owner) {
        return first("""
                SELECT display_name, institution, program, bio, discoverable,
                    EXISTS (SELECT 1 FROM learning_preferences WHERE user_id = p.user_id)
                    AND EXISTS (SELECT 1 FROM user_subjects WHERE user_id = p.user_id) AS completed
                FROM student_profiles p WHERE p.user_id = ?
                """, (row, i) -> new Profile(row.getString("display_name"), row.getString("institution"),
                        row.getString("program"), row.getString("bio"), row.getBoolean("discoverable"),
                        row.getBoolean("completed")), owner);
    }

    Optional<PublicProfile> publicProfile(long owner) {
        return first("""
                SELECT display_name, institution, program FROM student_profiles
                WHERE user_id = ? AND discoverable = TRUE
                """, (row, i) -> new PublicProfile(row.getString("display_name"),
                        row.getString("institution"), row.getString("program")), owner);
    }

    void saveProfile(long owner, ProfileInput values) {
        jdbc.update("""
                INSERT INTO student_profiles (user_id, display_name, institution, program, bio, discoverable)
                VALUES (?, ?, ?, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE display_name = incoming.display_name, institution = incoming.institution,
                    program = incoming.program, bio = incoming.bio, discoverable = incoming.discoverable
                """, owner, values.displayName(), values.institution(), values.program(), values.bio(),
                values.discoverable());
    }

    Optional<Preferences> preferences(long owner) {
        return first("""
                SELECT language, time_zone, learning_styles, skills, daily_focus_goal_minutes
                FROM learning_preferences WHERE user_id = ?
                """, (row, i) -> new Preferences(row.getString("language"), row.getString("time_zone"),
                        json.readValue(row.getString("learning_styles"), STRINGS),
                        json.readValue(row.getString("skills"), STRINGS),
                        row.getObject("daily_focus_goal_minutes", Integer.class)), owner);
    }

    void savePreferences(long owner, Preferences values) {
        jdbc.update("""
                INSERT INTO learning_preferences (user_id, language, time_zone, learning_styles, skills, daily_focus_goal_minutes)
                VALUES (?, ?, ?, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE language = incoming.language, time_zone = incoming.time_zone,
                    learning_styles = incoming.learning_styles, skills = incoming.skills,
                    daily_focus_goal_minutes = incoming.daily_focus_goal_minutes
                """, owner, values.language(), values.timeZone(), json.writeValueAsString(values.learningStyles()),
                json.writeValueAsString(values.skills()), values.dailyFocusGoalMinutes());
    }

    List<Slot> slots(long owner) {
        return jdbc.query("""
                SELECT weekday, starts_at, ends_at FROM availability_slots
                WHERE user_id = ? ORDER BY weekday, starts_at
                """, (row, i) -> new Slot(java.time.DayOfWeek.of(row.getInt("weekday")).name(),
                        row.getObject("starts_at", LocalTime.class).toString(),
                        row.getObject("ends_at", LocalTime.class).toString()), owner);
    }

    void replaceSlots(long owner, List<ProfileService.ParsedSlot> slots) {
        jdbc.update("DELETE FROM availability_slots WHERE user_id = ?", owner);
        for (var slot : slots) {
            jdbc.update("""
                    INSERT INTO availability_slots (user_id, weekday, starts_at, ends_at) VALUES (?, ?, ?, ?)
                    """, owner, slot.day().getValue(), slot.start(), slot.end());
        }
    }

    boolean subjectExists(long id) {
        return !jdbc.queryForList("SELECT id FROM subjects WHERE id = ?", Long.class, id).isEmpty();
    }

    List<CatalogItem> subjects(int limit, long offset) {
        return jdbc.query("SELECT id, name FROM subjects ORDER BY name, id LIMIT ? OFFSET ?", CATALOG, limit, offset);
    }

    List<CatalogItem> topics(long id, int limit, long offset) {
        return jdbc.query("""
                SELECT id, name FROM topics WHERE subject_id = ? ORDER BY name, id LIMIT ? OFFSET ?
                """, CATALOG, id, limit, offset);
    }

    List<String> selectedSubjects(long owner) {
        return jdbc.query("SELECT subject_id FROM user_subjects WHERE user_id = ? ORDER BY subject_id",
                (row, i) -> row.getString(1), owner);
    }

    void replaceSubjects(long owner, List<Long> ids) {
        jdbc.update("DELETE FROM user_subjects WHERE user_id = ?", owner);
        for (long id : ids) {
            jdbc.update("INSERT INTO user_subjects (user_id, subject_id) VALUES (?, ?)", owner, id);
        }
    }

    List<Goal> goals(long owner, int limit, long offset) {
        return jdbc.query("""
                SELECT id, subject_id, description, target_date, status FROM learning_goals
                WHERE user_id = ? ORDER BY id DESC LIMIT ? OFFSET ?
                """, GOAL, owner, limit, offset);
    }

    Optional<Goal> goal(long owner, long id) {
        return first("""
                SELECT id, subject_id, description, target_date, status FROM learning_goals
                WHERE user_id = ? AND id = ?
                """, GOAL, owner, id);
    }

    long addGoal(long owner, Long subject, String description, LocalDate date, String status) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO learning_goals (user_id, subject_id, description, target_date, status)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, owner);
            statement.setObject(2, subject);
            statement.setString(3, description);
            statement.setObject(4, date);
            statement.setString(5, status);
            return statement;
        }, keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }

    void updateGoal(long owner, long id, Long subject, String description, LocalDate date, String status) {
        jdbc.update("""
                UPDATE learning_goals SET subject_id = ?, description = ?, target_date = ?, status = ?
                WHERE user_id = ? AND id = ?
                """, subject, description, date, status, owner, id);
    }

    boolean deleteGoal(long owner, long id) {
        return jdbc.update("DELETE FROM learning_goals WHERE user_id = ? AND id = ?", owner, id) == 1;
    }

    private <T> Optional<T> first(String sql, RowMapper<T> mapper, Object... parameters) {
        return jdbc.query(sql, mapper, parameters).stream().findFirst();
    }
}
