package com.studysphere.productivity;

import java.net.URI;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.productivity.ProductivityService.Exam;
import com.studysphere.productivity.ProductivityService.ExamInput;
import com.studysphere.productivity.ProductivityService.FocusInput;
import com.studysphere.productivity.ProductivityService.FocusSession;
import com.studysphere.productivity.ProductivityService.FocusSummary;
import com.studysphere.productivity.ProductivityService.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/v1")
class ProductivityController {

    private final ProductivityService productivity;

    ProductivityController(ProductivityService productivity) {
        this.productivity = productivity;
    }

    @GetMapping("/exams")
    Page<Exam> exams(@AuthenticationPrincipal Identity identity, @RequestParam(required = false) String from,
                     @RequestParam(required = false) String to, @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size) {
        return productivity.exams(identity.id(), from, to, page, size);
    }

    @PostMapping("/exams")
    ResponseEntity<Exam> createExam(@AuthenticationPrincipal Identity identity, @RequestBody ExamInput input) {
        Exam result = productivity.createExam(identity.id(), input);
        return ResponseEntity.created(URI.create("/api/v1/exams/" + result.id())).body(result);
    }

    @GetMapping("/exams/{id}")
    Exam exam(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        return productivity.exam(identity.id(), id);
    }

    @PatchMapping("/exams/{id}")
    Exam updateExam(@AuthenticationPrincipal Identity identity, @PathVariable String id,
                    @RequestBody ExamInput input) {
        return productivity.updateExam(identity.id(), id, input);
    }

    @DeleteMapping("/exams/{id}")
    ResponseEntity<Void> deleteExam(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        productivity.deleteExam(identity.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/focus/sessions")
    Page<FocusSession> sessions(@AuthenticationPrincipal Identity identity,
                                @RequestParam(required = false) String state,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return productivity.sessions(identity.id(), state, page, size);
    }

    @PostMapping("/focus/sessions")
    ResponseEntity<FocusSession> start(@AuthenticationPrincipal Identity identity, @RequestBody FocusInput input) {
        FocusSession result = productivity.start(identity.id(), input);
        return ResponseEntity.created(URI.create("/api/v1/focus/sessions/" + result.id())).body(result);
    }

    @GetMapping("/focus/sessions/{id}")
    FocusSession session(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        return productivity.session(identity.id(), id);
    }

    @PostMapping("/focus/sessions/{id}/complete")
    FocusSession complete(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        return productivity.complete(identity.id(), id);
    }

    @GetMapping("/focus/summary")
    FocusSummary summary(@AuthenticationPrincipal Identity identity) {
        return productivity.focusSummary(identity.id());
    }

    @ExceptionHandler(ProductivityFailure.class)
    ResponseEntity<ProblemDetail> domainFailure(ProductivityFailure failure) {
        return problem(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalidRequest() {
        return problem(400, "INVALID_REQUEST", "Send a valid productivity request.");
    }

    private ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        var problem = ProblemDetail.forStatus(status);
        problem.setDetail(detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
