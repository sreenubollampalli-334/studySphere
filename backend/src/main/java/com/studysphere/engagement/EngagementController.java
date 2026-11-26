package com.studysphere.engagement;

import com.studysphere.auth.AuthService.Identity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class EngagementController {
    private final EngagementService engagement;

    EngagementController(EngagementService engagement) {
        this.engagement = engagement;
    }

    @GetMapping("/analytics/overview")
    EngagementService.Overview overview(@AuthenticationPrincipal Identity user) {
        return engagement.overview(user.id());
    }

    @GetMapping("/achievements")
    EngagementService.Achievements achievements(@AuthenticationPrincipal Identity user) {
        return engagement.achievements(user.id());
    }

    @GetMapping("/me/streak")
    EngagementService.Streak streak(@AuthenticationPrincipal Identity user) {
        return engagement.streak(user.id());
    }
}
