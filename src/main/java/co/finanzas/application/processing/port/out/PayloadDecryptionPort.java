package co.finanzas.application.processing.port.out;
import co.finanzas.application.ingestion.model.EncryptedPayload;
public interface PayloadDecryptionPort { byte[] decrypt(EncryptedPayload payload, byte[] associatedData); }
