package com.rincoltech.bms.kernel;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/** Builds and writes RFC 9457 problem bodies in the one shape chapter 7 section 7.7 defines. */
public final class Problems {

    /** Placeholder host until the product domain is registered (chapter 7 section 7.7). */
    private static final String TYPE_BASE = "https://docs.bms.invalid/errors/";

    private Problems() {}

    public static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
        return of(status, code, title, detail, List.of());
    }

    public static ProblemDetail of(
            HttpStatus status, String code, String title, String detail, List<ApiException.FieldProblem> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("request_id", RequestContext.requestId());
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return problem;
    }

    public static ProblemDetail of(ApiException e) {
        return of(e.status(), e.code(), e.title(), e.getMessage(), e.errors());
    }

    /** For servlet filters, which run before Spring MVC's exception handling. */
    public static void write(HttpServletResponse response, ObjectMapper mapper, ProblemDetail problem)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
