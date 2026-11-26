package com.studysphere.engagement;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EngagementService {
    private static final String ACTIVITY = """
            SELECT 'FOCUS' kind,f.id event_id,l.recorded_at occurred,l.duration_seconds amount,0 total
            FROM focus_sessions f JOIN study_session_logs l ON l.focus_session_id=f.id WHERE f.user_id=?
            UNION ALL
            SELECT 'QUIZ',id,submitted_at,score,JSON_LENGTH(results)
            FROM quiz_attempts WHERE user_id=? AND submitted_at IS NOT NULL
            UNION ALL
            SELECT 'REVIEW',r.id,r.reviewed_at,1,0
            FROM flashcard_reviews r JOIN flashcards c ON c.id=r.card_id
            JOIN flashcard_decks d ON d.id=c.deck_id WHERE d.user_id=?
            UNION ALL
            SELECT 'TASK',id,completed_at,1,0
            FROM study_tasks WHERE user_id=? AND completed_at IS NOT NULL
            """;
    private final JdbcTemplate jdbc;

    public EngagementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Overview overview(long owner) {
        ZoneId zone = zone(owner);
        LocalDate today = LocalDate.now(zone);
        var days = new LinkedHashMap<LocalDate, long[]>();
        for (int offset = 13; offset >= 0; offset--) days.put(today.minusDays(offset), new long[3]);
        var trend = new ArrayList<QuizPoint>();
        long focus = 0, attempts = 0, correct = 0, questions = 0, reviews = 0;
        for (Activity event : activity(owner)) {
            if (event.occurred() == null) continue;
            LocalDate date = event.occurred().atZone(zone).toLocalDate();
            long[] day = days.get(date);
            switch (event.kind()) {
                case "FOCUS" -> { focus += event.amount(); if (day != null) day[0] += event.amount(); }
                case "QUIZ" -> {
                    attempts++;
                    correct += event.amount();
                    questions += event.total();
                    if (day != null) {
                        day[1]++;
                        trend.add(new QuizPoint(date, event.amount(), event.total()));
                    }
                }
                case "REVIEW" -> { reviews++; if (day != null) day[2]++; }
                default -> { }
            }
        }
        var daily = days.entrySet().stream()
                .map(row -> new Day(row.getKey(), row.getValue()[0], row.getValue()[1], row.getValue()[2])).toList();
        long tasks = count("SELECT COUNT(*) FROM study_tasks WHERE user_id=? AND state='COMPLETED'", owner);
        long goals = count("SELECT COUNT(*) FROM learning_goals WHERE user_id=?", owner);
        long completed = count("SELECT COUNT(*) FROM learning_goals WHERE user_id=? AND status='COMPLETED'", owner);
        return new Overview(zone.getId(), focus, tasks, attempts, correct, questions, reviews, completed, goals, daily, trend);
    }

    @Transactional
    public Achievements achievements(long owner) {
        var historical = activity(owner).stream().filter(EngagementService::earnsReward).toList();
        jdbc.batchUpdate("""
                INSERT INTO engagement_rewards (user_id,event_type,event_id,xp,earned_at)
                VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=engagement_rewards.event_id
                """, historical, 100, (statement, event) -> {
            statement.setLong(1, owner);
            statement.setString(2, event.kind());
            statement.setLong(3, event.id());
            statement.setInt(4, StudyActivityRecorded.Kind.valueOf(event.kind()).xp());
            statement.setObject(5, LocalDateTime.ofInstant(event.occurred(), ZoneOffset.UTC));
        });
        long xp = count("SELECT COALESCE(SUM(xp),0) FROM engagement_rewards WHERE user_id=?", owner);
        ZoneId zone = zone(owner);
        LocalDate today = LocalDate.now(zone);
        var days = rewardDays(owner, zone);
        Streak streak = calculateStreak(days, today);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long progress = days.stream().filter(day -> !day.isBefore(weekStart) && !day.isAfter(today)).count();
        return new Achievements(xp, 1 + xp / 100, streak, List.of(
                new Badge("FIRST_STUDY", "First study activity", "Complete your first study activity.", xp > 0),
                new Badge("THREE_DAY_STREAK", "Three-day streak", "Study on three consecutive days.", streak.longestDays() >= 3),
                new Badge("HUNDRED_XP", "100 XP", "Earn 100 experience points.", xp >= 100)),
                new Challenge("Study on three days", progress, 3, weekStart, progress >= 3));
    }

    public Streak streak(long owner) {
        // Streaks also reflect activity completed before the achievements page was opened.
        ZoneId zone = zone(owner);
        var days = rewardDays(owner, zone);
        activity(owner).stream().filter(EngagementService::earnsReward)
                .map(a -> a.occurred().atZone(zone).toLocalDate()).forEach(days::add);
        return calculateStreak(days, LocalDate.now(zone));
    }

    @EventListener
    public void record(StudyActivityRecorded event) {
        jdbc.update("""
                INSERT INTO engagement_rewards (user_id,event_type,event_id,xp,earned_at)
                VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=engagement_rewards.event_id
                """, event.userId(), event.kind().name(), event.eventId(), event.kind().xp(),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
    }

    private static boolean earnsReward(Activity event) {
        return event.occurred() != null && (!event.kind().equals("FOCUS") || event.amount() > 0);
    }

    static Streak calculateStreak(Set<LocalDate> days, LocalDate today) {
        int longest = 0, run = 0;
        LocalDate prior = null;
        for (LocalDate date : new TreeSet<>(days)) {
            if (date.isAfter(today)) break;
            run = prior != null && date.equals(prior.plusDays(1)) ? run + 1 : 1;
            longest = Math.max(longest, run);
            prior = date;
        }
        boolean current = prior != null && !prior.isBefore(today.minusDays(1));
        return new Streak(current ? run : 0, longest);
    }

    private Set<LocalDate> rewardDays(long owner, ZoneId zone) {
        return new TreeSet<>(jdbc.query("SELECT earned_at FROM engagement_rewards WHERE user_id=?",
                (row, i) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate(), owner));
    }

    private List<Activity> activity(long owner) {
        return jdbc.query(ACTIVITY + " ORDER BY occurred,event_id", (row, i) -> {
            LocalDateTime at = row.getObject("occurred", LocalDateTime.class);
            return new Activity(row.getString("kind"), row.getLong("event_id"),
                    at == null ? null : at.toInstant(ZoneOffset.UTC),
                    row.getLong("amount"), row.getLong("total"));
        }, owner, owner, owner, owner);
    }

    private long count(String query, long owner) {
        Long result = jdbc.queryForObject(query, Long.class, owner);
        return result == null ? 0 : result;
    }

    private ZoneId zone(long owner) {
        return jdbc.query("SELECT time_zone FROM learning_preferences WHERE user_id=?",
                (row, i) -> ZoneId.of(row.getString(1)), owner).stream().findFirst().orElse(ZoneOffset.UTC);
    }

    private record Activity(String kind, long id, Instant occurred, long amount, long total) {}
    public record Day(LocalDate date, long focusSeconds, long quizAttempts, long flashcardReviews) {}
    public record QuizPoint(LocalDate date, long score, long total) {}
    public record Overview(String timeZone, long focusSeconds, long completedTasks, long quizAttempts,
                           long correctAnswers, long totalQuestions, long flashcardReviews, long completedGoals,
                           long totalGoals, List<Day> daily, List<QuizPoint> quizTrend) {}
    public record Streak(int currentDays, int longestDays) {}
    public record Badge(String code, String title, String description, boolean earned) {}
    public record Challenge(String title, long progress, int target, LocalDate weekStart, boolean completed) {}
    public record Achievements(long xp, long level, Streak streak, List<Badge> badges, Challenge weeklyChallenge) {}
}
