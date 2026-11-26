package com.studysphere.cards;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.studysphere.cards.FlashcardSchedule.Rating.*;

class FlashcardScheduleTest {
    private final Instant now = Instant.parse("2026-11-25T12:00:00Z");

    @Test
    void intervalsDependOnlyOnCurrentReviewAndPreviousInterval() {
        assertThat(FlashcardSchedule.next(now, 3, AGAIN).dueAt()).isEqualTo("2026-11-25T12:10:00Z");
        assertThat(FlashcardSchedule.next(now, 3, HARD).intervalDays()).isEqualTo(4);
        assertThat(FlashcardSchedule.next(now, 3, GOOD).intervalDays()).isEqualTo(6);
        assertThat(FlashcardSchedule.next(now, 3, EASY).dueAt()).isEqualTo("2026-12-04T12:00:00Z");
        assertThat(FlashcardSchedule.next(now, 365, EASY).intervalDays()).isEqualTo(365);
    }
}
