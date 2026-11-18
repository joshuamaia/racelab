package dev.racelab.infrastructure.web;

import dev.racelab.application.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> api(ApiException e, HttpServletRequest req) {
        return build(e.status(), e.code(), e.getMessage(), e.details(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().toList();
        return build(400, "VALIDATION_FAILED", details.isEmpty() ? "Invalid request" : details.getFirst(), details, req);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> unreadable(Exception e, HttpServletRequest req) {
        return build(400, "VALIDATION_FAILED", "Malformed request: " + rootMessage(e), List.of(), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e, HttpServletRequest req) {
        return build(404, "NOT_FOUND", "No endpoint " + req.getRequestURI(), List.of(), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return build(405, "METHOD_NOT_ALLOWED", e.getMessage(), List.of(), req);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ErrorResponse> notAcceptable(HttpMediaTypeNotAcceptableException e, HttpServletRequest req) {
        return build(406, "NOT_ACCEPTABLE", e.getMessage(), List.of(), req);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void clientGone() {
        // SSE client disconnected: nothing to answer
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(500, "INTERNAL_ERROR", "Unexpected internal error. Use the correlation id to find the logs.", List.of(), req);
    }

    private ResponseEntity<ErrorResponse> build(int status, String code, String message, List<String> details,
                                                HttpServletRequest req) {
        HttpStatus http = HttpStatus.resolve(status);
        ErrorResponse body = new ErrorResponse(Instant.now(), status, http == null ? "Error" : http.getReasonPhrase(), code,
                message, details, MDC.get("correlationId"), req.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : (m.length() > 300 ? m.substring(0, 300) : m);
    }
}
