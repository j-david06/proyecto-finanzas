package co.finanzas.adapters.shared.ingestion;

public final class PayloadDecodingException extends RuntimeException {
    private final int status;
    private final String code;
    public PayloadDecodingException(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
