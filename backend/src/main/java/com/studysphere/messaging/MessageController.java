package com.studysphere.messaging;

import com.studysphere.auth.AuthService.Identity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/v1/conversations")
class MessageController {
    private final MessageService service;

    MessageController(MessageService service) { this.service = service; }

    @GetMapping
    MessageService.Page<MessageService.Conversation> conversations(@AuthenticationPrincipal Identity owner,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(owner.id(), page, size);
    }

    @PostMapping
    ResponseEntity<MessageService.Conversation> start(@AuthenticationPrincipal Identity owner, @RequestBody Start input) {
        var conversation = service.start(owner.id(), id(input.userId()));
        return ResponseEntity.created(java.net.URI.create("/api/v1/conversations/" + conversation.id())).body(conversation);
    }

    @GetMapping("/{conversation}/messages")
    MessageService.Page<MessageService.Message> messages(@AuthenticationPrincipal Identity owner,
            @PathVariable String conversation, @RequestParam(required = false) String afterId,
            @RequestParam(required = false) String beforeId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.messages(owner.id(), id(conversation), afterId == null ? null : id(afterId),
                beforeId == null ? null : id(beforeId), page, size);
    }

    @PostMapping(path = "/{conversation}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MessageService.Message> send(@AuthenticationPrincipal Identity owner, @PathVariable String conversation,
                                                @RequestBody Text input) {
        return created(conversation, service.send(owner.id(), id(conversation), input.body(), null));
    }

    @PostMapping(path = "/{conversation}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<MessageService.Message> attach(@AuthenticationPrincipal Identity owner, @PathVariable String conversation,
            @RequestParam(required = false) String body, @RequestParam MultipartFile file) {
        return created(conversation, service.send(owner.id(), id(conversation), body, file));
    }

    @PostMapping("/{conversation}/read")
    ResponseEntity<Void> read(@AuthenticationPrincipal Identity owner, @PathVariable String conversation, @RequestBody Read input) {
        service.read(owner.id(), id(conversation), id(input.messageId()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversation}/messages/{message}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Identity owner, @PathVariable String conversation, @PathVariable String message) {
        service.delete(owner.id(), id(conversation), id(message));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversation}/messages/{message}/report")
    ResponseEntity<Void> report(@AuthenticationPrincipal Identity owner, @PathVariable String conversation,
                                 @PathVariable String message, @RequestBody Report input) {
        service.report(owner.id(), id(conversation), id(message), input.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{conversation}/messages/{message}/attachment")
    void download(@AuthenticationPrincipal Identity owner, @PathVariable String conversation,
                  @PathVariable String message, HttpServletResponse response) throws IOException {
        var attachment = service.attachment(owner.id(), id(conversation), id(message));
        try (var data = attachment.content()) {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(attachment.name(), StandardCharsets.UTF_8).build().toString());
            data.transferTo(response.getOutputStream());
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> problem(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(failure.getBody());
    }

    private ResponseEntity<MessageService.Message> created(String id, MessageService.Message message) {
        return ResponseEntity.status(HttpStatusCode.valueOf(201)).body(message);
    }

    static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw MessageService.invalid("Invalid identifier.");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw MessageService.invalid("Invalid identifier."); }
    }

    record Start(String userId) {}
    record Text(String body) {}
    record Read(String messageId) {}
    record Report(String reason) {}
}
