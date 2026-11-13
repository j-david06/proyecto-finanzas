package co.finanzas.application.ingestion.port.out;

import co.finanzas.application.ingestion.model.EncryptedPayload;

public interface PayloadCipherPort {
    EncryptedPayload encrypt(byte[] plaintext, byte[] associatedData);
}
