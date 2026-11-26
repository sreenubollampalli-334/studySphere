package com.studysphere.auth.api;

import com.studysphere.auth.AuthFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class AuthExceptionHandler {

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<AuthProblem> domainFailure(AuthFailure failure) {
        return AuthProblem.of(failure.status(), failure.code(), failure.getMessage()).asResponse();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<AuthProblem> malformedBody() {
        return AuthProblem.of(400, "INVALID_REQUEST", "Send a valid JSON request body.").asResponse();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<AuthProblem> unsupportedType() {
        return AuthProblem.of(415, "UNSUPPORTED_MEDIA_TYPE", "Send an application/json request body.").asResponse();
    }
}
