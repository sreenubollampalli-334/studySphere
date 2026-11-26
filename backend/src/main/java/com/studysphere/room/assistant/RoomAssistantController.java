package com.studysphere.room.assistant;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.room.RoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/rooms/{room}")
class RoomAssistantController {
    private final RoomAssistantService service;

    RoomAssistantController(RoomAssistantService service) {
        this.service = service;
    }

    @GetMapping("/assistant")
    RoomAssistantService.Assistant assistant(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return service.assistant(user.id(), RoomService.id(room));
    }

    @PutMapping("/assistant/consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void consent(@AuthenticationPrincipal Identity user, @PathVariable String room, @RequestBody Consent input) {
        service.consent(user.id(), RoomService.id(room), input.consented());
    }

    @GetMapping("/notes")
    java.util.List<RoomAssistantService.Note> notes(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return service.notes(user.id(), RoomService.id(room));
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    RoomAssistantService.Note note(@AuthenticationPrincipal Identity user, @PathVariable String room, @RequestBody NoteInput input) {
        return service.addNote(user.id(), RoomService.id(room), input.text());
    }

    @DeleteMapping("/notes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Identity user, @PathVariable String room, @PathVariable String id) {
        service.removeNote(user.id(), RoomService.id(room), RoomService.id(id));
    }

    @PostMapping("/summaries")
    @ResponseStatus(HttpStatus.CREATED)
    RoomAssistantService.Summary summarize(@AuthenticationPrincipal Identity user, @PathVariable String room,
                                            @RequestBody Disclosure input) {
        return service.summarize(user.id(), RoomService.id(room), input.disclosed());
    }

    @PostMapping("/assistant/proposals/{id}/approve")
    RoomAssistantService.Proposal approve(@AuthenticationPrincipal Identity user, @PathVariable String room,
                                           @PathVariable String id) {
        return service.approve(user.id(), RoomService.id(room), RoomService.id(id));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail problem(ResponseStatusException error) {
        return error.getBody();
    }

    record Consent(Boolean consented) {}
    record NoteInput(String text) {}
    record Disclosure(Boolean disclosed) {}
}
