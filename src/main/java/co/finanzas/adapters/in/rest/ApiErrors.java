package co.finanzas.adapters.in.rest;

import co.finanzas.application.ingestion.model.IngestionProblem;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class ApiErrors {
    private ApiErrors() { }
    public record ErrorBody(String type, String title, int status, String code) { }
    public static ErrorBody body(int status, String code) { return new ErrorBody("about:blank", code, status, code); }
    public static int status(IngestionProblem problem) {
        return switch (problem.code()) {
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case IDEMPOTENCY_CONFLICT -> 409;
            case INVALID_MOVEMENT -> 422;
        };
    }
    public static void write(ObjectMapper mapper, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "ApiKey realm=\"ingestion\"");
        mapper.writeValue(response.getOutputStream(), body(status, code));
    }
}
