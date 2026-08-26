package com.rag.obsidian_rag.advice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String error, String message, Instant timestamp) {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "Bad request", e);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> serviceUnavailable(IllegalStateException e) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Service misconfigured or unavailable", e);
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> ioError(IOException e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "I/O error", e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", e);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, Exception e) {
        return ResponseEntity.status(status)
                .body(new ApiError(error, e.getMessage(), Instant.now()));
    }
}
