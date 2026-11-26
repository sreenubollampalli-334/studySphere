package com.studysphere.adaptive;

import com.studysphere.auth.AuthService.Identity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class MasteryController {
    private final MasteryService mastery;

    MasteryController(MasteryService mastery) {
        this.mastery = mastery;
    }

    @GetMapping("/analytics/topics")
    MasteryService.Page<MasteryService.Topic> topics(@AuthenticationPrincipal Identity identity,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return mastery.topics(identity.id(), page, size);
    }

    @GetMapping("/recommendations")
    MasteryService.Page<MasteryService.Recommendation> recommendations(@AuthenticationPrincipal Identity identity,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return mastery.recommendations(identity.id(), page, size);
    }

    @PostMapping("/recommendations/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void feedback(@AuthenticationPrincipal Identity identity, @PathVariable String id, @RequestBody Feedback input) {
        mastery.feedback(identity.id(), identifier(id), input.status());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail problem(ResponseStatusException failure) {
        return failure.getBody();
    }

    private static long identifier(String id) {
        try {
            long value = Long.parseLong(id);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // Return the same problem for invalid identifiers.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid recommendation identifier.");
    }

    record Feedback(String status) {}
}
