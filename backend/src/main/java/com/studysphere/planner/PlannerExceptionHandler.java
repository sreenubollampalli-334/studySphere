package com.studysphere.planner;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PlannerController.class)
class PlannerExceptionHandler {
    @ExceptionHandler(PlannerFailure.class)
    ResponseEntity<ProblemDetail> plannerFailure(PlannerFailure failure) {
        var problem = ProblemDetail.forStatus(failure.status());
        problem.setDetail(failure.getMessage());
        problem.setProperty("code", failure.code());
        return ResponseEntity.status(failure.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidRequest() {
        return plannerFailure(PlannerService.invalid("Send valid JSON or parameters."));
    }
}
