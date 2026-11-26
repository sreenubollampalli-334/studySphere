package com.studysphere.community;

import com.studysphere.auth.AuthService.Identity;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class CommunityController {
    private final CommunityService communities;

    CommunityController(CommunityService communities) {
        this.communities = communities;
    }

    @GetMapping("/communities")
    CommunityService.Page<CommunityService.Community> list(@AuthenticationPrincipal Identity user,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return communities.communities(user.id(),page,size);
    }

    @PostMapping("/communities")
    ResponseEntity<CommunityService.Community> create(@AuthenticationPrincipal Identity user,@RequestBody Create input) {
        var result=communities.create(user.id(),id(input.subjectId()),input.name(),input.description());
        return ResponseEntity.created(URI.create("/api/v1/communities/"+result.id())).body(result);
    }

    @GetMapping("/communities/{community}")
    CommunityService.Community detail(@AuthenticationPrincipal Identity user,@PathVariable String community) {
        return communities.community(user.id(),id(community));
    }

    @GetMapping("/communities/{community}/members")
    CommunityService.Page<CommunityService.Member> members(@AuthenticationPrincipal Identity user,@PathVariable String community,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return communities.members(user.id(),id(community),page,size);
    }

    @PutMapping("/communities/{community}/members/me")
    CommunityService.Community join(@AuthenticationPrincipal Identity user,@PathVariable String community) {
        return communities.join(user.id(),id(community));
    }

    @DeleteMapping("/communities/{community}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@AuthenticationPrincipal Identity user,@PathVariable String community) {
        communities.leave(user.id(),id(community));
    }

    @PatchMapping("/communities/{community}/members/{target}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeRole(@AuthenticationPrincipal Identity user,@PathVariable String community,@PathVariable String target,
            @RequestBody Role input) {
        communities.changeRole(user.id(),id(community),id(target),input.role());
    }

    @GetMapping("/posts")
    CommunityService.Page<CommunityService.Post> posts(@AuthenticationPrincipal Identity user,@RequestParam String communityId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return communities.posts(user.id(),id(communityId),page,size);
    }

    @PostMapping("/posts")
    ResponseEntity<CommunityService.Post> post(@AuthenticationPrincipal Identity user,@RequestBody Post input) {
        var result=communities.post(user.id(),id(input.communityId()),input.title(),input.body(),
                Boolean.TRUE.equals(input.allowAiSummary()));
        return ResponseEntity.created(URI.create("/api/v1/posts/"+result.id()+"/comments")).body(result);
    }

    @DeleteMapping("/posts/{post}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePost(@AuthenticationPrincipal Identity user,@PathVariable String post) {
        communities.deletePost(user.id(),id(post));
    }

    @PostMapping("/posts/{post}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reportPost(@AuthenticationPrincipal Identity user,@PathVariable String post,@RequestBody Report input) {
        communities.report(user.id(),id(post),null,input.reason());
    }

    @PostMapping("/posts/{post}/comments/{comment}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reportComment(@AuthenticationPrincipal Identity user,@PathVariable String post,@PathVariable String comment,
                       @RequestBody Report input) {
        communities.report(user.id(),id(post),id(comment),input.reason());
    }

    @GetMapping("/posts/{post}/comments")
    CommunityService.Page<CommunityService.Comment> comments(@AuthenticationPrincipal Identity user,@PathVariable String post,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return communities.comments(user.id(),id(post),page,size);
    }

    @PostMapping("/posts/{post}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    CommunityService.Comment comment(@AuthenticationPrincipal Identity user,@PathVariable String post,@RequestBody Comment input) {
        return communities.comment(user.id(),id(post),input.body());
    }

    @DeleteMapping("/posts/{post}/comments/{comment}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@AuthenticationPrincipal Identity user,@PathVariable String post,@PathVariable String comment) {
        communities.deleteComment(user.id(),id(post),id(comment));
    }

    @PutMapping("/posts/{post}/vote")
    CommunityService.Post vote(@AuthenticationPrincipal Identity user,@PathVariable String post,@RequestBody Vote input) {
        if(input.value()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Vote must be 1 or -1.");
        return communities.vote(user.id(),id(post),input.value());
    }

    @DeleteMapping("/posts/{post}/vote")
    CommunityService.Post unvote(@AuthenticationPrincipal Identity user,@PathVariable String post) {
        return communities.vote(user.id(),id(post),null);
    }

    @GetMapping("/posts/{post}/related")
    CommunityService.Page<CommunityService.Post> related(@AuthenticationPrincipal Identity user,@PathVariable String post) {
        return communities.related(user.id(),id(post));
    }

    @GetMapping("/communities/{community}/resources")
    CommunityService.Page<CommunityService.Resource> resources(@AuthenticationPrincipal Identity user,@PathVariable String community,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return communities.resources(user.id(),id(community),page,size);
    }

    @PutMapping("/communities/{community}/resources/{resource}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void share(@AuthenticationPrincipal Identity user,@PathVariable String community,@PathVariable String resource) {
        communities.share(user.id(),id(community),id(resource));
    }

    @DeleteMapping("/communities/{community}/resources/{resource}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unshare(@AuthenticationPrincipal Identity user,@PathVariable String community,@PathVariable String resource) {
        communities.unshare(user.id(),id(community),id(resource));
    }

    @GetMapping("/communities/{community}/resources/{resource}/download")
    void download(@AuthenticationPrincipal Identity user,@PathVariable String community,@PathVariable String resource,
                  HttpServletResponse response) throws IOException {
        var file=communities.download(user.id(),id(community),id(resource));
        try(var stream=file.content()) {
            response.setContentType(file.resource().mediaType());
            response.setContentLengthLong(file.resource().sizeBytes());
            response.setHeader("Cache-Control",CacheControl.noStore().getHeaderValue());
            response.setHeader("X-Content-Type-Options","nosniff");
            response.setHeader("Content-Disposition",ContentDisposition.attachment()
                    .filename(file.resource().fileName(),StandardCharsets.UTF_8).build().toString());
            stream.transferTo(response.getOutputStream());
        }
    }

    @PostMapping("/communities/{community}/summaries")
    CommunityService.Summary summary(@AuthenticationPrincipal Identity user,@PathVariable String community,@RequestBody Consent input) {
        return communities.summarize(user.id(),id(community),input.consent());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> failure(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(failure.getBody());
    }

    private static long id(String input) {
        try {
            long value=Long.parseLong(input);
            if(value>0) return value;
        } catch(NumberFormatException ignored) {
            // Use the same validation response for all malformed identifiers.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a valid identifier.");
    }

    record Create(String subjectId,String name,String description) {}
    record Role(String role) {}
    record Report(String reason) {}
    record Post(String communityId,String title,String body,Boolean allowAiSummary) {}
    record Comment(String body) {}
    record Vote(Integer value) {}
    record Consent(boolean consent) {}
}
