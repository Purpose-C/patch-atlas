package io.github.patchatlas.shared.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", safeDetail(ex.getMessage()), request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "malformed JSON body", request);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemDetail> typeMismatch(Exception ex, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "invalid request parameter", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> status(ResponseStatusException ex, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String detail = ex.getReason() == null ? status.getReasonPhrase() : safeDetail(ex.getReason());
        return problem(status, status.getReasonPhrase(), detail, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(NoResourceFoundException ex, WebRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", "resource not found", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, WebRequest request) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", "unsupported content type", request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> notAcceptable(
            HttpMediaTypeNotAcceptableException ex, WebRequest request) {
        return problem(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable", "not acceptable", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> methodNotAllowed(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "method not allowed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception ex, WebRequest request) {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "an unexpected error occurred",
                request);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String detail, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("about:blank"));
        try {
            pd.setInstance(ServletUriComponentsBuilder.fromCurrentRequest().build().toUri());
        } catch (IllegalStateException ignored) {
            // non-request threads
        }
        return ResponseEntity.status(status).body(pd);
    }

    private static String safeDetail(String message) {
        if (message == null || message.isBlank()) {
            return "invalid request";
        }
        String m = message.length() > 256 ? message.substring(0, 256) : message;
        if (m.contains("Exception") || m.contains("\n") || m.contains("Caused by")) {
            return "invalid request";
        }
        return m;
    }
}
