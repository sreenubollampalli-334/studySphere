package com.studysphere.planner;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerRecurrenceTest {
    private final ZoneId zone = ZoneId.of("America/New_York");

    @Test
    void dailyRecurrenceReturnsToAnchorWallTimeAfterGap() {
        Instant anchor = Instant.parse("2026-03-07T07:30:00Z");
        Instant gap = PlannerService.nextOccurrence(anchor, anchor, "DAILY", zone);
        assertThat(gap).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
        assertThat(PlannerService.nextOccurrence(anchor, gap, "DAILY", zone))
                .isEqualTo(Instant.parse("2026-03-09T06:30:00Z"));
    }

    @Test
    void weeklyRecurrenceChoosesEarlierOffsetInOverlap() {
        Instant anchor = Instant.parse("2026-10-25T05:30:00Z");
        assertThat(PlannerService.nextOccurrence(anchor, anchor, "WEEKLY", zone))
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
    }
}
