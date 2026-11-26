package com.studysphere.mentor;

import com.studysphere.auth.AuthService.Identity;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai/conversations")
class MentorController {
    private final MentorService service;
    private final MentorStream stream;

    MentorController(MentorService service, MentorStream stream) {
        this.service = service;
        this.stream = stream;
    }

    @GetMapping
    MentorService.Page<MentorService.Conversation> list(@AuthenticationPrincipal Identity owner,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.conversations(owner.id(), page, size);
    }

    @PostMapping
    ResponseEntity<MentorService.Conversation> create(@AuthenticationPrincipal Identity owner,
            @RequestBody(required = false) Title input) {
        var conversation = service.create(owner.id(), input == null ? null : input.title());
        return ResponseEntity.created(URI.create("/api/v1/ai/conversations/" + conversation.id()))
                .cacheControl(CacheControl.noStore()).body(conversation);
    }

    @GetMapping("/{conversation}")
    MentorService.Conversation read(@AuthenticationPrincipal Identity owner, @PathVariable String conversation) {
        return service.conversation(owner.id(), id(conversation));
    }

    @PatchMapping("/{conversation}")
    MentorService.Conversation rename(@AuthenticationPrincipal Identity owner, @PathVariable String conversation,
            @RequestBody Title input) {
        return service.rename(owner.id(), id(conversation), input.title());
    }

    @DeleteMapping("/{conversation}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Identity owner, @PathVariable String conversation) {
        service.delete(owner.id(), id(conversation));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{conversation}/messages")
    MentorService.Page<MentorService.Message> messages(@AuthenticationPrincipal Identity owner,
            @PathVariable String conversation, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.messages(owner.id(), id(conversation), page, size);
    }

    @PostMapping(path = "/{conversation}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Identity owner, @PathVariable String conversation,
            @RequestBody Prompt input) {
        var emitter = stream.open(owner.id(), id(conversation), input.message(), input.mode());
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(emitter);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> problem(ResponseStatusException failure) {
        var response = ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (failure.getStatusCode().value() == 429) response.header("Retry-After", "60");
        return response.body(failure.getBody());
    }

    static long id(String input) {
        try {
            if (!input.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(input);
        } catch (NumberFormatException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identifier.");
        }
    }

    record Title(String title) {}
    record Prompt(String message, String mode) {}
}
