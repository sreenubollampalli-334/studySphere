package com.studysphere.document;

import com.studysphere.auth.AuthService.Identity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/documents")
class DocumentController {
    private final DocumentService documents;

    DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    DocumentService.Page list(@AuthenticationPrincipal Identity user,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size) {
        return documents.list(user.id(), page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DocumentService.View create(@AuthenticationPrincipal Identity user, @RequestBody Create request) {
        return documents.create(user.id(), id(request.resourceId()));
    }

    @GetMapping("/{id}/status")
    DocumentService.View status(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        return documents.status(user.id(), id(id));
    }

    @PostMapping("/{id}/retry")
    DocumentService.View retry(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        return documents.retry(user.id(), id(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Identity user, @PathVariable String id) {
        documents.delete(user.id(), id(id));
    }

    @PostMapping("/{id}/ask")
    DocumentService.Answer ask(@AuthenticationPrincipal Identity user, @PathVariable String id, @RequestBody Ask request) {
        return documents.ask(user.id(), id(id), request.question());
    }

    private static long id(String value) {
        try {
            long identifier = Long.parseLong(value);
            if (identifier > 0) return identifier;
        } catch (NumberFormatException ignored) {
            // Invalid input is handled consistently below.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identifier.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail problem(ResponseStatusException error) {
        return error.getBody();
    }

    record Create(String resourceId) {}
    record Ask(String question) {}
}
