package co.finanzas.application.ingestion.port.out;

public interface DigestPort {
    byte[] sha256(byte[] value);
    boolean matches(byte[] expected, byte[] actual);
}
