package com.studysphere.adaptive.proposal;

import com.studysphere.auth.AuthService.Identity;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/plans/proposals")
class ProposalController {
    private final ProposalService proposals;

    ProposalController(ProposalService proposals) {
        this.proposals = proposals;
    }

    @GetMapping
    ProposalService.Page list(@AuthenticationPrincipal Identity owner,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return proposals.list(owner.id(), page, size);
    }

    @PostMapping
    ResponseEntity<ProposalService.Preview> create(@AuthenticationPrincipal Identity owner,
            @RequestBody ProposalService.Input input) {
        var proposal = proposals.create(owner.id(), input);
        return ResponseEntity.created(URI.create("/api/v1/plans/proposals/" + proposal.id())).body(proposal);
    }

    @GetMapping("/{id}")
    ProposalService.Preview get(@AuthenticationPrincipal Identity owner, @PathVariable String id) {
        return proposals.get(owner.id(), identifier(id));
    }

    @PostMapping("/{id}/approve")
    ProposalService.Preview approve(@AuthenticationPrincipal Identity owner, @PathVariable String id) {
        return proposals.approve(owner.id(), identifier(id));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail problem(ResponseStatusException error) {
        return error.getBody();
    }

    private static long identifier(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
            // Invalid IDs use the same response.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid proposal identifier.");
    }
}
