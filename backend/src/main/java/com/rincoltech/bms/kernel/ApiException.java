package com.rincoltech.bms.kernel;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A failure the API reports to the caller as an RFC 9457 problem (chapter 7 section 7.7).
 * {@code code} is the stable machine-readable identifier the frontend switches on.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final List<FieldProblem> errors;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, List.of());
    }

    public ApiException(HttpStatus status, String code, String title, String detail, List<FieldProblem> errors) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.errors = List.copyOf(errors);
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "Not found", "The resource does not exist.");
    }

    public static ApiException validation(List<FieldProblem> errors) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "validation_failed",
                "Validation failed",
                "One or more fields are invalid.",
                errors);
    }

    public static ApiException rule(String code, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, "Business rule failed", detail);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public List<FieldProblem> errors() {
        return errors;
    }

    /** One entry of the {@code errors} array. */
    public record FieldProblem(String field, String code, String message) {}
}
