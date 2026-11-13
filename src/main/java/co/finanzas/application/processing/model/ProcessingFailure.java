package co.finanzas.application.processing.model;

/** Safe, bounded failure codes; never expose payloads or infrastructure exception messages. */
public final class ProcessingFailure extends RuntimeException {
    public enum Code { KEY_UNAVAILABLE, PAYLOAD_AUTHENTICATION_FAILED, PAYLOAD_HASH_MISMATCH,
        INVALID_PAYLOAD, PARSER_UNAVAILABLE, ACCOUNT_NOT_AUTHORIZED, ACCOUNT_MAPPING_REQUIRED,
        BANK_EVENT_CONFLICT, TEMPORARY_FAILURE }
    private final Code code;
    private final boolean retryable;
    public ProcessingFailure(Code code, boolean retryable) {
        super(code.name()); this.code = code; this.retryable = retryable;
    }
    public Code code() { return code; }
    public boolean retryable() { return retryable; }
}
