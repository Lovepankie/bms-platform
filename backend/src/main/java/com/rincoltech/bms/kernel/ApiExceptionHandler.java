package com.rincoltech.bms.kernel;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to the problem shape of chapter 7 section 7.7. Unexpected errors return only
 * the request id: no exception text, no stack, nothing that could carry personal data.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(Problems.of(e));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(Problems.of(
                        HttpStatus.BAD_REQUEST,
                        "malformed_request",
                        "Malformed request",
                        "Parameter '" + e.getName() + "' has the wrong format."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e) {
        log.warn(
                "Data integrity violation: {}",
                e.getMostSpecificCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Problems.of(
                        HttpStatus.CONFLICT, "conflict", "Conflict", "The request conflicts with existing data."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(Problems.of(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "internal_error",
                        "Internal error",
                        "An unexpected error occurred."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiException.FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiException.FieldProblem(
                        snakeCase(f.getField()),
                        "invalid",
                        f.getDefaultMessage() == null ? "Invalid value." : f.getDefaultMessage()))
                .toList();
        ApiException api = ApiException.validation(errors);
        return ResponseEntity.status(api.status()).body(Problems.of(api));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        String code = switch (status) {
            case BAD_REQUEST -> "malformed_request";
            case NOT_FOUND -> "not_found";
            case METHOD_NOT_ALLOWED -> "method_not_allowed";
            case UNSUPPORTED_MEDIA_TYPE -> "unsupported_media_type";
            case NOT_ACCEPTABLE -> "not_acceptable";
            default -> status.is5xxServerError() ? "internal_error" : "request_failed";
        };
        ProblemDetail problem = Problems.of(status, code, status.getReasonPhrase(), status.getReasonPhrase() + ".");
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    static String snakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
