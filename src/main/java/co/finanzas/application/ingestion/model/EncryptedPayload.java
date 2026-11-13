package co.finanzas.application.ingestion.model;

public record EncryptedPayload(byte[] bytes, String keyId) {
    public EncryptedPayload { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
}
