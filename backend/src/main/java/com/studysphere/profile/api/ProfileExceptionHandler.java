package com.studysphere.profile.api;

import com.studysphere.profile.ProfileFailure;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = ProfileController.class)
class ProfileExceptionHandler {

    @ExceptionHandler(ProfileFailure.class)
    ResponseEntity<ProblemDetail> domainFailure(ProfileFailure failure) {
        return problem(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> invalidParameter() {
        return problem(400, "INVALID_REQUEST", "Send valid query and path parameters.");
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
