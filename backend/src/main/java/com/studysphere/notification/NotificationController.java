package com.studysphere.notification;

import com.studysphere.auth.AuthService.Identity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class NotificationController {
    private final NotificationService notifications;

    NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/notifications")
    NotificationService.Page list(@AuthenticationPrincipal Identity identity,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return notifications.list(identity.id(), page, size);
    }

    @PutMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        long parsed;
        try {
            parsed = Long.parseLong(id);
            if (parsed <= 0) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid notification identifier.");
        }
        notifications.read(identity.id(), parsed);
    }

    @GetMapping("/me/notification-preferences")
    NotificationService.Preferences preferences(@AuthenticationPrincipal Identity identity) {
        return notifications.preferences(identity.id());
    }

    @PutMapping("/me/notification-preferences")
    NotificationService.Preferences save(@AuthenticationPrincipal Identity identity,
            @RequestBody NotificationService.Preferences preferences) {
        return notifications.save(identity.id(), preferences);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail failure(ResponseStatusException failure) {
        return failure.getBody();
    }
}
