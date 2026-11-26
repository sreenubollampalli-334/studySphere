package com.studysphere.profile.api;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.planner.PlannerService;
import com.studysphere.productivity.ProductivityService;
import com.studysphere.resource.ResourceService;
import com.studysphere.room.RoomService;
import com.studysphere.quiz.QuizService;
import com.studysphere.adaptive.MasteryService;
import com.studysphere.engagement.EngagementService;
import com.studysphere.profile.ProfileService;
import com.studysphere.profile.ProfileService.Availability;
import com.studysphere.profile.ProfileService.CatalogItem;
import com.studysphere.profile.ProfileService.Goal;
import com.studysphere.profile.ProfileService.GoalInput;
import com.studysphere.profile.ProfileService.Page;
import com.studysphere.profile.ProfileService.Preferences;
import com.studysphere.profile.ProfileService.Profile;
import com.studysphere.profile.ProfileService.ProfileInput;
import com.studysphere.profile.ProfileService.SubjectSelection;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ProfileController {

    private final ProfileService profiles;
    private final PlannerService planner;
    private final ProductivityService productivity;
    private final ResourceService resources;
    private final RoomService rooms;
    private final QuizService quizzes;
    private final MasteryService mastery;
    private final EngagementService engagement;

    ProfileController(ProfileService profiles, PlannerService planner, ProductivityService productivity,
                      ResourceService resources, RoomService rooms, QuizService quizzes, MasteryService mastery,
                      EngagementService engagement) {
        this.profiles = profiles;
        this.planner = planner;
        this.productivity = productivity;
        this.resources = resources;
        this.rooms = rooms;
        this.quizzes = quizzes;
        this.mastery = mastery;
        this.engagement = engagement;
    }

    @GetMapping("/me/profile")
    Profile profile(@AuthenticationPrincipal Identity identity) {
        return profiles.profile(identity.id());
    }

    @PutMapping("/me/profile")
    Profile saveProfile(@AuthenticationPrincipal Identity identity, @RequestBody ProfileInput profile) {
        return profiles.saveProfile(identity.id(), profile);
    }

    @GetMapping("/me/preferences")
    Preferences preferences(@AuthenticationPrincipal Identity identity) {
        return profiles.preferences(identity.id());
    }

    @PutMapping("/me/preferences")
    Preferences savePreferences(@AuthenticationPrincipal Identity identity, @RequestBody Preferences preferences) {
        return profiles.savePreferences(identity.id(), preferences);
    }

    @GetMapping("/me/availability")
    Availability availability(@AuthenticationPrincipal Identity identity) {
        return profiles.availability(identity.id());
    }

    @PutMapping("/me/availability")
    Availability saveAvailability(@AuthenticationPrincipal Identity identity, @RequestBody Availability availability) {
        return profiles.saveAvailability(identity.id(), availability);
    }

    @GetMapping("/subjects")
    Page<CatalogItem> subjects(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        return profiles.subjects(page, size);
    }

    @GetMapping("/subjects/{id}/topics")
    Page<CatalogItem> topics(@PathVariable String id, @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
        return profiles.topics(id, page, size);
    }

    @GetMapping("/me/subjects")
    SubjectSelection selectedSubjects(@AuthenticationPrincipal Identity identity) {
        return profiles.selectedSubjects(identity.id());
    }

    @PutMapping("/me/subjects")
    SubjectSelection saveSubjects(@AuthenticationPrincipal Identity identity, @RequestBody SubjectSelection subjects) {
        return profiles.saveSubjects(identity.id(), subjects);
    }

    @GetMapping("/me/goals")
    Page<Goal> goals(@AuthenticationPrincipal Identity identity, @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size) {
        return profiles.goals(identity.id(), page, size);
    }

    @PostMapping("/me/goals")
    ResponseEntity<Goal> createGoal(@AuthenticationPrincipal Identity identity, @RequestBody GoalInput input) {
        Goal goal = profiles.createGoal(identity.id(), input);
        return ResponseEntity.created(URI.create("/api/v1/me/goals/" + goal.id())).body(goal);
    }

    @PatchMapping("/me/goals/{id}")
    Goal updateGoal(@AuthenticationPrincipal Identity identity, @PathVariable String id, @RequestBody GoalInput input) {
        return profiles.updateGoal(identity.id(), id, input);
    }

    @DeleteMapping("/me/goals/{id}")
    ResponseEntity<Void> deleteGoal(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        profiles.deleteGoal(identity.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dashboard")
    Dashboard dashboard(@AuthenticationPrincipal Identity identity) {
        long owner = identity.id();
        String timeZone = profiles.preferences(owner).timeZone();
        ZoneId zone = timeZone == null ? ZoneOffset.UTC : ZoneId.of(timeZone);
        LocalDate today = LocalDate.now(zone);
        var tasks = planner.todaysTasks(owner, today.atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant());
        return new Dashboard(profiles.profile(owner).onboardingComplete(), tasks, productivity.upcomingExams(owner, today),
                resources.recent(owner), rooms.activeRooms(owner), quizzes.summaries(owner),
                mastery.recommendations(owner, 0, 5).items(), engagement.streak(owner).currentDays());
    }

    record Dashboard(boolean onboardingComplete, List<?> todayTasks, List<?> upcomingExams,
                     List<?> recentResources, List<?> activeRooms, List<?> quizSummaries,
                     List<?> recommendations, Integer streak) {}
}
