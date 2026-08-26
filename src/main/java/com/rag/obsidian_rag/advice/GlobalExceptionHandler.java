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

    /**
     * Uniform error payload for all handled exceptions.
     *
     * @param error     coarse error category label
     * @param message   exception message (may be null)
     * @param timestamp server time at which the error was handled
     */
    public record ApiError(String error, String message, Instant timestamp) {
    }

    /**
     * Maps argument validation failures to HTTP 400.
     *
     * @param e thrown exception
     * @return error payload with status 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "Bad request", e);
    }

    /**
     * Maps misconfiguration (e.g. missing NVIDIA_API_KEY) to HTTP 503.
     *
     * @param e thrown exception
     * @return error payload with status 503
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> serviceUnavailable(IllegalStateException e) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Service misconfigured or unavailable", e);
    }

    /**
     * Maps filesystem/IO failures to HTTP 500.
     *
     * @param e thrown exception
     * @return error payload with status 500
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> ioError(IOException e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "I/O error", e);
    }

    /**
     * Catch-all handler: logs the stack trace and maps anything unmatched to HTTP 500.
     *
     * @param e thrown exception
     * @return error payload with status 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", e);
    }

    /**
     * Assembles the response entity for a handled exception.
     *
     * @param status HTTP status to return
     * @param error  coarse category label
     * @param e      caught exception whose message becomes the detail
     * @return response entity carrying the {@link ApiError} payload
     */
    private ResponseEntity<ApiError> build(HttpStatus status, String error, Exception e) {
        return ResponseEntity.status(status)
                .body(new ApiError(error, e.getMessage(), Instant.now()));
    }
}
