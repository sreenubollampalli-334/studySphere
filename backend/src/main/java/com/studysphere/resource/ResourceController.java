package com.studysphere.resource;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.resource.ResourceService.*;
import com.studysphere.resource.storage.StorageException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
class ResourceController {
    private static final String FOLDERS = "resource_folders";
    private static final String COLLECTIONS = "collections";
    private final ResourceService library;

    ResourceController(ResourceService library) { this.library = library; }

    @GetMapping("/resources")
    Page<Resource> list(@AuthenticationPrincipal Identity owner, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String q,
            @RequestParam(required=false) String tag, @RequestParam(required=false) String folderId,
            @RequestParam(required=false) String collectionId) {
        return library.list(owner.id(), page, size, q, tag, folderId, collectionId, "all");
    }

    @PostMapping(path="/resources", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Resource> upload(@AuthenticationPrincipal Identity owner, @RequestParam MultipartFile file,
            @RequestParam(required=false) String title, @RequestParam(required=false) String tags,
            @RequestParam(required=false) String folderId) {
        var added = library.upload(owner.id(), file, title, tags, folderId);
        return created("/resources/" + added.id(), added);
    }

    @GetMapping("/resources/{id}")
    Resource fetch(@AuthenticationPrincipal Identity owner, @PathVariable String id) {
        return library.get(owner.id(), ResourceService.id(id));
    }

    @PatchMapping("/resources/{id}")
    Resource update(@AuthenticationPrincipal Identity owner, @PathVariable String id, @RequestBody UpdateResource changes) {
        return library.update(owner.id(), ResourceService.id(id), changes);
    }

    @DeleteMapping("/resources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Identity owner, @PathVariable String id) {
        library.delete(owner.id(), ResourceService.id(id));
    }

    @GetMapping("/resources/{id}/download")
    void download(@AuthenticationPrincipal Identity owner, @PathVariable String id, HttpServletResponse response)
            throws IOException {
        var download = library.download(owner.id(), ResourceService.id(id));
        var resource = download.resource();
        try (var content = download.content()) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentLengthLong(resource.sizeBytes());
            response.setContentType(resource.mediaType());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(resource.fileName(), StandardCharsets.UTF_8).build().toString());
            response.setHeader("X-Content-Type-Options", "nosniff");
            content.transferTo(response.getOutputStream());
        }
    }

    @GetMapping("/resources/recent")
    Page<Resource> recent(@AuthenticationPrincipal Identity owner, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return library.list(owner.id(), page, size, null, null, null, null, "recent");
    }

    @GetMapping("/resources/{id}/permissions")
    List<Permission> permissions(@AuthenticationPrincipal Identity owner, @PathVariable String id) {
        return library.permissions(owner.id(), ResourceService.id(id));
    }

    @PostMapping("/resources/{id}/permissions")
    @ResponseStatus(HttpStatus.CREATED)
    Permission share(@AuthenticationPrincipal Identity owner, @PathVariable String id, @RequestBody Email input) {
        return library.share(owner.id(), ResourceService.id(id), input.email());
    }

    @DeleteMapping("/resources/{id}/permissions/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Identity owner, @PathVariable String id, @PathVariable String userId) {
        library.revoke(owner.id(), ResourceService.id(id), ResourceService.id(userId));
    }

    @GetMapping("/folders")
    Page<Named> folders(@AuthenticationPrincipal Identity owner, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return library.names(owner.id(), FOLDERS, page, size);
    }

    @PostMapping("/folders")
    ResponseEntity<Named> createFolder(@AuthenticationPrincipal Identity owner, @RequestBody Name input) {
        var folder = library.createName(owner.id(), FOLDERS, input.name());
        return created("/folders/" + folder.id(), folder);
    }

    @DeleteMapping("/folders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteFolder(@AuthenticationPrincipal Identity owner, @PathVariable String id) {
        library.deleteName(owner.id(), ResourceService.id(id), FOLDERS);
    }

    @GetMapping("/collections")
    Page<Named> collections(@AuthenticationPrincipal Identity owner, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return library.names(owner.id(), COLLECTIONS, page, size);
    }

    @PostMapping("/collections")
    ResponseEntity<Named> createCollection(@AuthenticationPrincipal Identity owner, @RequestBody Name input) {
        var collection = library.createName(owner.id(), COLLECTIONS, input.name());
        return created("/collections/" + collection.id(), collection);
    }

    @DeleteMapping("/collections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteCollection(@AuthenticationPrincipal Identity owner, @PathVariable String id) {
        library.deleteName(owner.id(), ResourceService.id(id), COLLECTIONS);
    }

    @PutMapping("/collections/{id}/resources/{resourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addMember(@AuthenticationPrincipal Identity owner, @PathVariable String id, @PathVariable String resourceId) {
        library.member(owner.id(), ResourceService.id(id), ResourceService.id(resourceId), true);
    }

    @DeleteMapping("/collections/{id}/resources/{resourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@AuthenticationPrincipal Identity owner, @PathVariable String id, @PathVariable String resourceId) {
        library.member(owner.id(), ResourceService.id(id), ResourceService.id(resourceId), false);
    }

    @GetMapping("/bookmarks")
    Page<Resource> bookmarks(@AuthenticationPrincipal Identity owner, @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return library.list(owner.id(), page, size, null, null, null, null, "bookmarks");
    }

    @PutMapping("/bookmarks/{resourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void bookmark(@AuthenticationPrincipal Identity owner, @PathVariable String resourceId) {
        library.bookmark(owner.id(), ResourceService.id(resourceId), true);
    }

    @DeleteMapping("/bookmarks/{resourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unbookmark(@AuthenticationPrincipal Identity owner, @PathVariable String resourceId) {
        library.bookmark(owner.id(), ResourceService.id(resourceId), false);
    }

    @ExceptionHandler(ResourceFailure.class)
    ProblemDetail resourceFailure(ResourceFailure failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(failure.status()), failure.getMessage());
    }

    @ExceptionHandler(StorageException.class)
    ProblemDetail unavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Resource storage is unavailable.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail uploadTooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds 20 MiB.");
    }

    private static <T> ResponseEntity<T> created(String path, T item) {
        return ResponseEntity.created(URI.create("/api/v1" + path)).body(item);
    }

    record Name(String name) {}
    record Email(String email) {}
}
