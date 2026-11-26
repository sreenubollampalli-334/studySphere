package com.studysphere.engagement;

import java.time.Instant;

public record StudyActivityRecorded(long userId, Kind kind, long eventId, Instant occurredAt) {
    public enum Kind {
        FOCUS(10), QUIZ(20), REVIEW(5), TASK(10);

        private final int xp;

        Kind(int xp) {
            this.xp = xp;
        }

        int xp() {
            return xp;
        }
    }
}
