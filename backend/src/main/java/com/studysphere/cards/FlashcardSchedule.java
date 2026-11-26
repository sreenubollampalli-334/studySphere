package com.studysphere.cards;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

final class FlashcardSchedule {
    enum Rating { AGAIN, HARD, GOOD, EASY }

    record Next(Instant dueAt, int intervalDays) {}

    private FlashcardSchedule() {}

    static Next next(Instant now, int previous, Rating rating) {
        if (rating == Rating.AGAIN) return new Next(now.plus(10, ChronoUnit.MINUTES), 0);
        int interval = switch (rating) {
            case HARD -> Math.max(1, (int) Math.ceil(previous * 1.2));
            case GOOD -> Math.max(1, previous * 2);
            case EASY -> Math.max(4, previous * 3);
            case AGAIN -> throw new IllegalStateException("Already handled");
        };
        interval = Math.min(365, interval);
        return new Next(now.plus(interval, ChronoUnit.DAYS), interval);
    }
}
