package com.studysphere.matching;

import java.util.List;
import java.util.Optional;

import com.studysphere.profile.ProfileService;
import com.studysphere.profile.ProfileService.Availability;
import com.studysphere.profile.ProfileService.Goal;
import com.studysphere.profile.ProfileService.Page;
import com.studysphere.profile.ProfileService.Preferences;
import com.studysphere.profile.ProfileService.PublicProfile;
import com.studysphere.profile.ProfileService.Slot;
import com.studysphere.profile.ProfileService.SubjectSelection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchingServiceTest {
    @Test
    void ranksAllFiveSignalsAndOmitsProfileEvidence() {
        var connections = mock(ConnectionService.class);
        var profiles = mock(ProfileService.class);
        when(connections.candidates(1)).thenReturn(List.of(2L, 3L));
        when(profiles.publicProfile(2)).thenReturn(Optional.of(new PublicProfile("Language match", null, null)));
        when(profiles.publicProfile(3)).thenReturn(Optional.of(new PublicProfile("Study partner", null, null)));
        when(profiles.selectedSubjects(1)).thenReturn(new SubjectSelection(List.of("7")));
        when(profiles.selectedSubjects(2)).thenReturn(new SubjectSelection(List.of("2")));
        when(profiles.selectedSubjects(3)).thenReturn(new SubjectSelection(List.of("7")));
        when(profiles.preferences(1)).thenReturn(new Preferences("en", "UTC", List.of("Practice"), List.of("SQL"), null));
        when(profiles.preferences(2)).thenReturn(new Preferences("en", "UTC", List.of("Reading"), List.of(), null));
        when(profiles.preferences(3)).thenReturn(new Preferences("en", "UTC", List.of("Practice"), List.of("Schemas"), null));
        var sharedWindow = new Availability(List.of(new Slot("MONDAY", "18:00", "19:00")));
        when(profiles.availability(1)).thenReturn(sharedWindow);
        when(profiles.availability(2)).thenReturn(new Availability(List.of()));
        when(profiles.availability(3)).thenReturn(sharedWindow);
        when(profiles.goals(1, 0, 100)).thenReturn(new Page<>(
                List.of(new Goal("1", "7", "Private goal details", null, "ACTIVE")), 0, 100, false));
        when(profiles.goals(2, 0, 100)).thenReturn(new Page<>(List.of(), 0, 100, false));
        when(profiles.goals(3, 0, 100)).thenReturn(new Page<>(
                List.of(new Goal("2", "7", "Private goal instructions", null, "ACTIVE")), 0, 100, false));

        var result = new MatchingService(connections, profiles).partners(1, 0, 1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.items()).singleElement().satisfies(partner -> {
            assertThat(partner.userId()).isEqualTo("3");
            assertThat(partner.score()).isEqualTo(100);
            assertThat(partner.reasons()).containsExactly("Shared subjects", "Overlapping study availability",
                    "Similar study goals", "Complementary skills", "Compatible learning preferences");
            assertThat(partner.toString()).doesNotContain("Private goal", "18:00", "Schemas");
        });
        assertThat(new MatchingService(connections, profiles).partners(1, 1, 1).items())
                .singleElement().satisfies(partner -> assertThat(partner.score()).isEqualTo(5));
    }
}
