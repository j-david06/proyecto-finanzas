package co.finanzas.adapters.out.crypto;

import co.finanzas.application.ingestion.port.out.DigestPort;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha256Digests implements DigestPort {
    @Override public byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
    @Override public boolean matches(byte[] expected, byte[] actual) {
        return MessageDigest.isEqual(expected, actual);
    }
}
