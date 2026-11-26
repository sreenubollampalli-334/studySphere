package com.studysphere.adaptive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MasteryServiceTest {
    @Test
    void mapsScoresAndMissingEvidenceDeterministically() {
        assertThat(MasteryService.state(null)).isEqualTo("INSUFFICIENT");
        assertThat(MasteryService.state(59)).isEqualTo("WEAK");
        assertThat(MasteryService.state(60)).isEqualTo("DEVELOPING");
        assertThat(MasteryService.state(79)).isEqualTo("DEVELOPING");
        assertThat(MasteryService.state(80)).isEqualTo("MASTERED");
    }

    @Test
    void explainsRecommendationsUsingRecordedEvidence() {
        var baseline = MasteryService.recommendation("13", "SQL", 2, null);
        assertThat(baseline.kind()).isEqualTo("STUDY_TOPIC");
        assertThat(baseline.reason()).contains("2 of 3");
        var weak = MasteryService.recommendation("13", "SQL", 5, 40);
        assertThat(weak.kind()).isEqualTo("REVISION");
        assertThat(weak.reason()).contains("40%", "5 recorded responses");
    }
}
