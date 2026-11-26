package com.studysphere.matching;

import java.net.URI;
import java.util.List;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.profile.ProfileService.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1")
class MatchingController {
    private final MatchingService matching;
    private final ConnectionService connections;

    MatchingController(MatchingService matching, ConnectionService connections) {
        this.matching = matching;
        this.connections = connections;
    }

    @GetMapping("/partners")
    Page<MatchingService.Partner> partners(@AuthenticationPrincipal Identity caller,
                                         @RequestParam(defaultValue="0") int page,
                                         @RequestParam(defaultValue="20") int size) {
        return matching.partners(caller.id(), page, size);
    }

    @GetMapping("/connections/requests")
    List<ConnectionService.Request> requests(@AuthenticationPrincipal Identity caller) {
        return connections.requests(caller.id());
    }

    @PostMapping("/connections/requests")
    ResponseEntity<ConnectionService.Request> request(@AuthenticationPrincipal Identity caller, @RequestBody PeerInput input) {
        var request = connections.send(caller.id(), ConnectionService.parseId(input.userId()));
        return ResponseEntity.created(URI.create("/api/v1/connections/requests/" + request.id())).body(request);
    }

    @PatchMapping("/connections/requests/{id}")
    ConnectionService.Request respond(@AuthenticationPrincipal Identity caller, @PathVariable String id,
                                        @RequestBody ResponseInput input) {
        return connections.respond(caller.id(), ConnectionService.parseId(id), input.status());
    }

    @GetMapping("/connections")
    List<ConnectionService.Peer> connections(@AuthenticationPrincipal Identity caller) {
        return connections.connections(caller.id());
    }

    @DeleteMapping("/connections/{userId}")
    ResponseEntity<Void> disconnect(@AuthenticationPrincipal Identity caller, @PathVariable String userId) {
        connections.disconnect(caller.id(), ConnectionService.parseId(userId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/blocks")
    List<ConnectionService.Peer> blocks(@AuthenticationPrincipal Identity caller) {
        return connections.blocks(caller.id());
    }

    @PutMapping("/blocks/{userId}")
    ResponseEntity<Void> block(@AuthenticationPrincipal Identity caller, @PathVariable String userId) {
        connections.block(caller.id(), ConnectionService.parseId(userId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/blocks/{userId}")
    ResponseEntity<Void> unblock(@AuthenticationPrincipal Identity caller, @PathVariable String userId) {
        connections.unblock(caller.id(), ConnectionService.parseId(userId));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MatchingFailure.class)
    ResponseEntity<ProblemDetail> failure(MatchingFailure failure) {
        return problem(failure.status(), failure.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> invalidParameter() {
        return problem(400, "Send valid query and path parameters.");
    }

    private static ResponseEntity<ProblemDetail> problem(int status, String message) {
        var detail = ProblemDetail.forStatus(status);
        detail.setDetail(message);
        detail.setProperty("code", switch (status) {
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            default -> "INVALID_REQUEST";
        });
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail);
    }

    record PeerInput(String userId) {}
    record ResponseInput(String status) {}
}
