package com.studysphere.engagement;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EngagementServiceTest {
    @Test
    void yesterdayRetainsCurrentStreakAndMissingDayEndsIt() {
        LocalDate today = LocalDate.of(2030, 1, 10);
        var days = Set.of(today.minusDays(4), today.minusDays(2), today.minusDays(1));
        assertThat(EngagementService.calculateStreak(days, today))
                .isEqualTo(new EngagementService.Streak(2, 2));
        assertThat(EngagementService.calculateStreak(days, today.plusDays(1)))
                .isEqualTo(new EngagementService.Streak(0, 2));
    }
}
