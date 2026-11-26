package com.studysphere.profile;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IllformedLocaleException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {

    private final ProfileStore store;

    ProfileService(ProfileStore store) {
        this.store = store;
    }

    public Profile profile(long owner) {
        return store.profile(owner).orElseGet(() -> new Profile(null, null, null, null, false, false));
    }

    @Transactional
    public Profile saveProfile(long owner, ProfileInput input) {
        if (input == null) throw invalid("A profile is required.");
        var cleaned = new ProfileInput(required(input.displayName(), 100, "displayName"),
                optional(input.institution(), 160, "institution"), optional(input.program(), 160, "program"),
                optional(input.bio(), 1200, "bio"), Boolean.TRUE.equals(input.discoverable()));
        lockOwner(owner);
        store.saveProfile(owner, cleaned);
        return profile(owner);
    }

    public Optional<PublicProfile> publicProfile(long owner) {
        return store.publicProfile(owner);
    }

    public Preferences preferences(long owner) {
        return store.preferences(owner).orElseGet(() -> new Preferences(null, null, List.of(), List.of(), null));
    }

    @Transactional
    public Preferences savePreferences(long owner, Preferences input) {
        if (input == null) throw invalid("Preferences are required.");
        Integer goal = input.dailyFocusGoalMinutes();
        if (goal != null && (goal < 1 || goal > 1440)) throw invalid("Daily focus goal must be between 1 and 1440 minutes.");
        var cleaned = new Preferences(language(input.language()), timezone(input.timeZone()),
                labels(input.learningStyles(), "learningStyles"), labels(input.skills(), "skills"), goal);
        lockOwner(owner);
        store.savePreferences(owner, cleaned);
        return cleaned;
    }

    public Availability availability(long owner) {
        return new Availability(store.slots(owner));
    }

    @Transactional
    public Availability saveAvailability(long owner, Availability input) {
        if (input == null || input.slots() == null || input.slots().size() > 100) {
            throw invalid("Provide up to 100 availability slots.");
        }
        var slots = new ArrayList<ParsedSlot>(input.slots().size());
        for (Slot slot : input.slots()) slots.add(parseSlot(slot));
        slots.sort(Comparator.comparing(ParsedSlot::day).thenComparing(ParsedSlot::start));
        for (int index = 1; index < slots.size(); index++) {
            var preceding = slots.get(index - 1);
            var current = slots.get(index);
            if (preceding.day() == current.day() && preceding.end().isAfter(current.start())) {
                throw invalid("Availability slots on the same day cannot overlap.");
            }
        }
        lockOwner(owner);
        store.replaceSlots(owner, slots);
        return availability(owner);
    }

    public Page<CatalogItem> subjects(int page, int size) {
        return page(store.subjects(limit(page, size), (long) page * size), page, size);
    }

    public Page<CatalogItem> topics(String subjectId, int page, int size) {
        long id = id(subjectId);
        if (!store.subjectExists(id)) throw missing("The subject could not be found.");
        return page(store.topics(id, limit(page, size), (long) page * size), page, size);
    }

    public SubjectSelection selectedSubjects(long owner) {
        return new SubjectSelection(store.selectedSubjects(owner));
    }

    @Transactional
    public SubjectSelection saveSubjects(long owner, SubjectSelection input) {
        if (input == null || input.subjectIds() == null || input.subjectIds().size() > 100) {
            throw invalid("Provide up to 100 subject IDs.");
        }
        var ids = new LinkedHashSet<Long>();
        for (String subjectId : input.subjectIds()) ids.add(subject(subjectId));
        lockOwner(owner);
        store.replaceSubjects(owner, List.copyOf(ids));
        return selectedSubjects(owner);
    }

    public Page<Goal> goals(long owner, int page, int size) {
        return page(store.goals(owner, limit(page, size), (long) page * size), page, size);
    }

    @Transactional
    public Goal createGoal(long owner, GoalInput input) {
        if (input == null) throw invalid("A goal is required.");
        Long subject = goalSubject(input.subjectId());
        String description = required(input.description(), 500, "description");
        LocalDate date = date(input.targetDate());
        String status = status(input.status() == null ? "ACTIVE" : input.status());
        lockOwner(owner);
        long id = store.addGoal(owner, subject, description, date, status);
        return ownedGoal(owner, id);
    }

    @Transactional
    public Goal updateGoal(long owner, String goalId, GoalInput input) {
        if (input == null) throw invalid("A goal update is required.");
        long id = id(goalId);
        lockOwner(owner);
        Goal existing = ownedGoal(owner, id);
        Long subject = goalSubject(input.subjectId() == null ? existing.subjectId() : input.subjectId());
        String description = required(input.description() == null ? existing.description() : input.description(),
                500, "description");
        LocalDate date = date(input.targetDate() == null ? existing.targetDate() : input.targetDate());
        String status = status(input.status() == null ? existing.status() : input.status());
        store.updateGoal(owner, id, subject, description, date, status);
        return ownedGoal(owner, id);
    }

    @Transactional
    public void deleteGoal(long owner, String goalId) {
        long id = id(goalId);
        lockOwner(owner);
        if (!store.deleteGoal(owner, id)) throw missing("The goal could not be found.");
    }

    private Goal ownedGoal(long owner, long id) {
        return store.goal(owner, id).orElseThrow(() -> missing("The goal could not be found."));
    }

    private void lockOwner(long owner) {
        if (!store.lockOwner(owner)) throw new ProfileFailure(401, "AUTHENTICATION_REQUIRED", "Sign in to continue.");
    }

    private Long goalSubject(String value) {
        return value == null || value.isBlank() ? null : subject(value);
    }

    private long subject(String value) {
        long id = id(value);
        if (!store.subjectExists(id)) throw invalid("Choose a subject from the catalog.");
        return id;
    }

    private static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw invalid("The identifier must be a positive decimal number.");
        }
    }

    private static String language(String value) {
        String tag = required(value, 35, "language");
        try {
            Locale locale = new Locale.Builder().setLanguageTag(tag).build();
            if (!locale.getLanguage().isEmpty()) return locale.toLanguageTag();
        } catch (IllformedLocaleException ignored) {
            // Use a single validation response for ill-formed and unspecified languages.
        }
        throw invalid("Choose a valid language tag.");
    }

    private static String timezone(String value) {
        String zone = required(value, 80, "timeZone");
        if (!ZoneId.getAvailableZoneIds().contains(zone)) throw invalid("Choose an IANA time zone.");
        return zone;
    }

    private static List<String> labels(List<String> values, String field) {
        if (values == null) return List.of();
        if (values.size() > 30) throw invalid(field + " accepts up to 30 entries.");
        var distinct = new LinkedHashSet<String>();
        for (String value : values) distinct.add(required(value, 80, field));
        return List.copyOf(distinct);
    }

    private static ParsedSlot parseSlot(Slot slot) {
        try {
            if (slot == null || slot.day() == null || slot.start() == null || slot.end() == null
                    || !slot.start().matches("[0-9]{2}:[0-9]{2}") || !slot.end().matches("[0-9]{2}:[0-9]{2}")) {
                throw new IllegalArgumentException();
            }
            var start = LocalTime.parse(slot.start());
            var end = LocalTime.parse(slot.end());
            if (!end.isAfter(start)) throw new IllegalArgumentException();
            return new ParsedSlot(DayOfWeek.valueOf(slot.day()), start, end);
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw invalid("Use a weekday and HH:mm times, with the end after the start.");
        }
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            var date = LocalDate.parse(value);
            if (date.getYear() >= 1000 && date.getYear() <= 9999) return date;
        } catch (DateTimeParseException ignored) {
            // Invalid calendar dates and out-of-range years use the same response.
        }
        throw invalid("Enter a valid ISO target date.");
    }

    private static String status(String value) {
        if ("ACTIVE".equals(value) || "COMPLETED".equals(value)) return value;
        throw invalid("Goal status must be ACTIVE or COMPLETED.");
    }

    private static String required(String value, int max, String field) {
        String cleaned = optional(value, max, field);
        if (cleaned == null) throw invalid(field + " is required.");
        return cleaned;
    }

    private static String optional(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.strip();
        if (cleaned.codePointCount(0, cleaned.length()) > max) {
            throw invalid(field + " must be at most " + max + " characters.");
        }
        return cleaned;
    }

    private static int limit(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("Use a nonnegative page and a size from 1 to 100.");
        return size + 1;
    }

    private static <T> Page<T> page(List<T> rows, int page, int size) {
        boolean hasNext = rows.size() > size;
        return new Page<>(hasNext ? List.copyOf(rows.subList(0, size)) : List.copyOf(rows), page, size, hasNext);
    }

    private static ProfileFailure invalid(String message) {
        return new ProfileFailure(400, "INVALID_PROFILE_INPUT", message);
    }

    private static ProfileFailure missing(String message) {
        return new ProfileFailure(404, "NOT_FOUND", message);
    }

    public record ProfileInput(String displayName, String institution, String program, String bio,
                               Boolean discoverable) {}
    public record Profile(String displayName, String institution, String program, String bio,
                          boolean discoverable, boolean onboardingComplete) {}
    public record PublicProfile(String displayName, String institution, String program) {}
    public record Preferences(String language, String timeZone, List<String> learningStyles,
                              List<String> skills, Integer dailyFocusGoalMinutes) {}
    public record Slot(String day, String start, String end) {}
    public record Availability(List<Slot> slots) {}
    public record SubjectSelection(List<String> subjectIds) {}
    public record CatalogItem(String id, String name) {}
    public record Page<T>(List<T> items, int page, int size, boolean hasNext) {}
    public record GoalInput(String subjectId, String description, String targetDate, String status) {}
    public record Goal(String id, String subjectId, String description, String targetDate, String status) {}

    record ParsedSlot(DayOfWeek day, LocalTime start, LocalTime end) {}
}
