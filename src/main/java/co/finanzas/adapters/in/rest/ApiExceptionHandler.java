package co.finanzas.adapters.in.rest;

import co.finanzas.application.ingestion.model.IngestionProblem;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IngestionProblem.class)
    ResponseEntity<ApiErrors.ErrorBody> application(IngestionProblem problem) {
        return response(ApiErrors.status(problem), problem.code().name());
    }
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ApiErrors.ErrorBody> api(ApiProblem problem) { return response(problem.status(), problem.code()); }
    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrors.ErrorBody> malformed(Exception exception) { return response(400, "INVALID_REQUEST"); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiErrors.ErrorBody> storage(DataAccessException exception) { return response(503, "STORAGE_UNAVAILABLE"); }

    private ResponseEntity<ApiErrors.ErrorBody> response(int status, String code) {
        var builder = ResponseEntity.status(status).header("Cache-Control", "no-store")
                .header("Content-Type", "application/problem+json");
        if (status == 401) builder.header("WWW-Authenticate", "ApiKey realm=\"ingestion\"");
        return builder.body(ApiErrors.body(status, code));
    }
}
