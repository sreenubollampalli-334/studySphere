package com.studysphere.room;

import static com.studysphere.room.RoomService.id;

import com.studysphere.auth.AuthService.Identity;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class RoomController {
    private final RoomService rooms;

    RoomController(RoomService rooms) { this.rooms = rooms; }

    @GetMapping("/rooms")
    RoomService.Page<RoomService.Room> list(@AuthenticationPrincipal Identity user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return rooms.list(user.id(), page, size);
    }

    @PostMapping("/rooms")
    ResponseEntity<RoomService.Room> create(@AuthenticationPrincipal Identity user, @RequestBody RoomService.RoomInput input) {
        var room = rooms.create(user.id(), input);
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + room.id())).body(room);
    }

    @GetMapping("/rooms/{room}")
    RoomService.Room get(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return rooms.get(user.id(), id(room));
    }

    @PatchMapping("/rooms/{room}")
    RoomService.Room update(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @RequestBody RoomService.RoomInput input) {
        return rooms.update(user.id(), id(room), input);
    }

    @DeleteMapping("/rooms/{room}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        rooms.delete(user.id(), id(room));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rooms/{room}/members")
    List<RoomService.Member> members(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return rooms.members(user.id(), id(room));
    }

    @PutMapping("/rooms/{room}/members/me")
    RoomService.Room join(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return rooms.join(user.id(), id(room));
    }

    @DeleteMapping("/rooms/{room}/members/me")
    ResponseEntity<Void> leave(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        rooms.leave(user.id(), id(room));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/rooms/{room}/members/{target}")
    ResponseEntity<Void> changeRole(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @PathVariable String target, @RequestBody RoleInput input) {
        rooms.changeRole(user.id(), id(room), id(target), input.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/rooms/{room}/members/{target}")
    ResponseEntity<Void> removeMember(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @PathVariable String target) {
        rooms.removeMember(user.id(), id(room), id(target));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/room-invitations")
    List<RoomService.Room> incomingInvitations(@AuthenticationPrincipal Identity user) {
        return rooms.incomingInvitations(user.id());
    }

    @GetMapping("/rooms/{room}/invitations")
    List<RoomService.Invitation> invitations(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return rooms.invitations(user.id(), id(room));
    }

    @PostMapping("/rooms/{room}/invitations")
    ResponseEntity<RoomService.Invitation> invite(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @RequestBody InviteInput input) {
        var invitation = rooms.invite(user.id(), id(room), input.email());
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + room + "/invitations/" + invitation.userId()))
                .body(invitation);
    }

    @DeleteMapping("/rooms/{room}/invitations/{target}")
    ResponseEntity<Void> revokeInvitation(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @PathVariable String target) {
        rooms.revokeInvitation(user.id(), id(room), id(target));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rooms/{room}/agenda")
    List<RoomService.AgendaItem> agenda(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return rooms.agenda(user.id(), id(room));
    }

    @PostMapping("/rooms/{room}/agenda")
    ResponseEntity<RoomService.AgendaItem> addAgenda(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @RequestBody AgendaInput input) {
        var item = rooms.addAgenda(user.id(), id(room), input.text());
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + room + "/agenda/" + item.id())).body(item);
    }

    @DeleteMapping("/rooms/{room}/agenda/{item}")
    ResponseEntity<Void> removeAgenda(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @PathVariable String item) {
        rooms.removeAgenda(user.id(), id(room), id(item));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rooms/{room}/resources")
    List<RoomService.Resource> resources(@AuthenticationPrincipal Identity user, @PathVariable String room) {
        return rooms.resources(user.id(), id(room));
    }

    @PostMapping("/rooms/{room}/resources")
    ResponseEntity<RoomService.Resource> share(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @RequestBody ShareInput input) {
        var resource = rooms.shareResource(user.id(), id(room), id(input.resourceId()));
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + room + "/resources/" + resource.id()))
                .body(resource);
    }

    @DeleteMapping("/rooms/{room}/resources/{resource}")
    ResponseEntity<Void> unshare(@AuthenticationPrincipal Identity user, @PathVariable String room,
            @PathVariable String resource) {
        rooms.unshareResource(user.id(), id(room), id(resource));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/rooms/{room}/resources/{resource}/download")
    void download(@AuthenticationPrincipal Identity user, @PathVariable String room, @PathVariable String resource,
            HttpServletResponse response) throws IOException {
        var file = rooms.download(user.id(), id(room), id(resource));
        try (var stream = file.content()) {
            response.setContentType(file.resource().mediaType());
            response.setContentLengthLong(file.resource().sizeBytes());
            response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(file.resource().fileName(), StandardCharsets.UTF_8).build().toString());
            stream.transferTo(response.getOutputStream());
        }
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> failure(ResponseStatusException failure) {
        var problem = ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason());
        problem.setProperty("code", "ROOM_" + failure.getStatusCode().value());
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(problem);
    }

    record RoleInput(String role) {}
    record InviteInput(String email) {}
    record AgendaInput(String text) {}
    record ShareInput(String resourceId) {}
}
