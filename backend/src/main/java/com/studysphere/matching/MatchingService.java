package com.studysphere.matching;

import com.studysphere.profile.ProfileService;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
class MatchingService {
    private static final double WEEK_MINUTES = 7 * 24 * 60;
    private final ConnectionService connections;
    private final ProfileService profiles;

    MatchingService(ConnectionService connections, ProfileService profiles) {
        this.connections = connections;
        this.profiles = profiles;
    }

    ProfileService.Page<Partner> partners(long owner, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw ConnectionService.invalid("Send a valid page and size.");
        LocalDate monday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        Signals caller = signals(owner, monday);
        var ranked = new ArrayList<Partner>();
        for (long user : connections.candidates(owner)) {
            profiles.publicProfile(user).ifPresent(profile -> ranked.add(rank(user, profile, caller, signals(user, monday))));
        }
        ranked.sort(Comparator.comparingInt(Partner::score).reversed().thenComparingLong(p -> Long.parseLong(p.userId())));
        long offset = (long) page * size;
        int start = (int) Math.min(offset, ranked.size());
        int end = Math.min(start + size, ranked.size());
        return new ProfileService.Page<>(List.copyOf(ranked.subList(start, end)), page, size, end < ranked.size());
    }

    private Signals signals(long owner, LocalDate monday) {
        var preferences = profiles.preferences(owner);
        var goals = new HashSet<String>();
        for (int page = 0; ; page++) {
            var batch = profiles.goals(owner, page, 100);
            for (var goal : batch.items()) {
                if ("ACTIVE".equals(goal.status())) {
                    goals.add(goal.subjectId() == null ? goal.description().strip().toLowerCase(Locale.ROOT) : "subject:" + goal.subjectId());
                }
            }
            if (!batch.hasNext()) break;
        }
        ZoneId zone = preferences.timeZone() == null ? ZoneOffset.UTC : ZoneId.of(preferences.timeZone());
        List<Window> windows = new ArrayList<>();
        var weekStart = monday.atStartOfDay(ZoneOffset.UTC).toInstant();
        for (var slot : profiles.availability(owner).slots()) {
            LocalDate day = monday.plusDays(DayOfWeek.valueOf(slot.day()).getValue() - 1L);
            double start = Duration.between(weekStart, day.atTime(LocalTime.parse(slot.start())).atZone(zone).toInstant()).toMinutes();
            double duration = Duration.between(day.atTime(LocalTime.parse(slot.start())).atZone(zone).toInstant(),
                    day.atTime(LocalTime.parse(slot.end())).atZone(zone).toInstant()).toMinutes();
            if (duration <= 0) continue;
            double wrapped = Math.floorMod((long) start, (long) WEEK_MINUTES);
            windows.add(new Window(wrapped, Math.min(WEEK_MINUTES, wrapped + duration)));
            if (wrapped + duration > WEEK_MINUTES) windows.add(new Window(0, wrapped + duration - WEEK_MINUTES));
        }
        return new Signals(new HashSet<>(profiles.selectedSubjects(owner).subjectIds()), List.copyOf(windows),
                goals, normalize(preferences.skills()), normalize(preferences.learningStyles()), preferences.language());
    }

    private static Partner rank(long user, ProfileService.PublicProfile profile, Signals left, Signals right) {
        double subjects = overlap(left.subjects(), right.subjects());
        double availability = availability(left.windows(), right.windows());
        double goals = overlap(left.goals(), right.goals());
        double skills = left.skills().isEmpty() || right.skills().isEmpty() ? 0 : 1 - overlap(left.skills(), right.skills());
        double styles = overlap(left.styles(), right.styles());
        double language = left.language() != null && left.language().equalsIgnoreCase(right.language()) ? 1 : 0;
        double preferences = (styles + language) / 2;
        int score = (int) Math.round(30 * subjects + 25 * availability + 20 * goals + 15 * skills + 10 * preferences);
        var reasons = new ArrayList<String>();
        if (subjects > 0) reasons.add("Shared subjects");
        if (availability > 0) reasons.add("Overlapping study availability");
        if (goals > 0) reasons.add("Similar study goals");
        if (skills > 0) reasons.add("Complementary skills");
        if (preferences > 0) reasons.add("Compatible learning preferences");
        return new Partner(Long.toString(user), profile.displayName(), profile.institution(), profile.program(), score, List.copyOf(reasons));
    }

    private static double overlap(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        long common = left.stream().filter(right::contains).count();
        return (double) common / (left.size() + right.size() - common);
    }

    private static double availability(List<Window> left, List<Window> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        double common = 0;
        double total = left.stream().mapToDouble(w -> w.end() - w.start()).sum()
                + right.stream().mapToDouble(w -> w.end() - w.start()).sum();
        for (Window a : left) for (Window b : right) {
            common += Math.max(0, Math.min(a.end(), b.end()) - Math.max(a.start(), b.start()));
        }
        return Math.min(1, common / (total - common));
    }

    private static Set<String> normalize(List<String> labels) {
        var normalized = new HashSet<String>();
        for (String label : labels) normalized.add(label.strip().toLowerCase(Locale.ROOT));
        return normalized;
    }

    record Partner(String userId, String displayName, String institution, String program, int score, List<String> reasons) {}
    private record Signals(Set<String> subjects, List<Window> windows, Set<String> goals,
                           Set<String> skills, Set<String> styles, String language) {}
    private record Window(double start, double end) {}
}
