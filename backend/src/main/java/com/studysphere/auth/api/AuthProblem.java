package com.studysphere.auth.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

public record AuthProblem(String type, String title, int status, String code, String detail) {

    public static AuthProblem of(int status, String code, String detail) {
        return new AuthProblem("about:blank", HttpStatus.valueOf(status).getReasonPhrase(), status, code, detail);
    }

    ResponseEntity<AuthProblem> asResponse() {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(this);
    }

    public void writeTo(HttpServletResponse response, ObjectMapper json) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), this);
    }
}
