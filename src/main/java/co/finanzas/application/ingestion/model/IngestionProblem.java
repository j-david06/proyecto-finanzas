package co.finanzas.application.ingestion.model;

public final class IngestionProblem extends RuntimeException {
    public enum Code { UNAUTHORIZED, FORBIDDEN, NOT_FOUND, IDEMPOTENCY_CONFLICT, INVALID_MOVEMENT }
    private final Code code;

    public IngestionProblem(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
