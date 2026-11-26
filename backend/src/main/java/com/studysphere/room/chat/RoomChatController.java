package com.studysphere.room.chat;

import com.studysphere.auth.AuthService.Identity;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/rooms/{room}/messages")
class RoomChatController {
    private final RoomChatService chat;

    RoomChatController(RoomChatService chat) { this.chat = chat; }

    @GetMapping
    RoomChatService.Page history(@AuthenticationPrincipal Identity owner, @PathVariable String room,
            @RequestParam(required = false) String afterId, @RequestParam(required = false) String beforeId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return chat.history(owner.id(), id(room), afterId == null ? null : id(afterId),
                beforeId == null ? null : id(beforeId), page, size);
    }

    @PostMapping
    ResponseEntity<RoomChatService.Entry> send(@AuthenticationPrincipal Identity owner,
            @PathVariable String room, @RequestBody Text text) {
        var entry = chat.send(owner.id(), id(room), text.body());
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + room + "/messages/" + entry.id())).body(entry);
    }

    @PutMapping("/{message}/pin")
    ResponseEntity<Void> pin(@AuthenticationPrincipal Identity owner, @PathVariable String room, @PathVariable String message) {
        chat.pin(owner.id(), id(room), id(message), true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{message}/pin")
    ResponseEntity<Void> unpin(@AuthenticationPrincipal Identity owner, @PathVariable String room, @PathVariable String message) {
        chat.pin(owner.id(), id(room), id(message), false);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> problem(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(failure.getBody());
    }

    static long id(String input) {
        try {
            if (!input.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(input);
        } catch (NumberFormatException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identifier.");
        }
    }

    record Text(String body) {}
}
