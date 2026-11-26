package com.studysphere.admin;

import com.studysphere.admin.AdminService.ReportStatus;
import com.studysphere.admin.ReportIntake.Kind;
import com.studysphere.auth.AuthService.Identity;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin")
class AdminController {
    private final AdminService admin;

    AdminController(AdminService admin) {
        this.admin = admin;
    }

    @GetMapping("/reports")
    ResponseEntity<AdminService.Page<AdminService.Report>> reports(@RequestParam(required=false) ReportStatus status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return noStore(admin.reports(status,page,size));
    }

    @PatchMapping("/reports/{kind}/{targetId}/{reporterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void review(@AuthenticationPrincipal Identity actor, @PathVariable Kind kind,
                @PathVariable String targetId, @PathVariable String reporterId, @RequestBody Review input) {
        admin.review(actor.id(),kind,id(targetId),id(reporterId),input.status(),input.reason(),input.hideContent());
    }

    @GetMapping("/restrictions")
    ResponseEntity<AdminService.Page<AdminService.Restriction>> restrictions(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return noStore(admin.restrictions(page,size));
    }

    @PutMapping("/restrictions/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restrict(@AuthenticationPrincipal Identity actor, @PathVariable String userId, @RequestBody Restrict input) {
        admin.restrict(actor.id(),id(userId),input.reason(),input.expiresAt());
    }

    @DeleteMapping("/restrictions/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void lift(@AuthenticationPrincipal Identity actor, @PathVariable String userId, @RequestBody Lift input) {
        admin.lift(actor.id(),id(userId),input.reason());
    }

    @GetMapping("/audits")
    ResponseEntity<AdminService.Page<AdminService.Audit>> audits(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return noStore(admin.audits(page,size));
    }

    @GetMapping("/usage")
    ResponseEntity<AdminService.Usage> usage(@RequestParam(defaultValue="30") int days) {
        return noStore(admin.usage(days));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> failure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(failure.getBody());
    }

    private static <T> ResponseEntity<T> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(data);
    }

    private static long id(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Keep the response consistent for malformed identifiers.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid identifier.");
    }

    record Review(ReportStatus status, String reason, boolean hideContent) {}
    record Restrict(String reason, Instant expiresAt) {}
    record Lift(String reason) {}
}
