package co.finanzas.ingestion;

import co.finanzas.adapters.out.crypto.AesGcmPayloadCipher;
import co.finanzas.application.ingestion.model.EncryptedPayload;
import co.finanzas.application.processing.model.ProcessingFailure;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PayloadDecryptionTest {
    private final AesGcmPayloadCipher cipher=new AesGcmPayloadCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","v1");
    private final byte[] plaintext="private financial event".getBytes(StandardCharsets.UTF_8);
    private final byte[] aad="owner:connection:event".getBytes(StandardCharsets.UTF_8);
    @Test void decryptsOnlyWithTheOriginalAssociatedIdentity() {
        var encrypted=cipher.encrypt(plaintext,aad);
        assertArrayEquals(plaintext,cipher.decrypt(encrypted,aad));
        var error=assertThrows(ProcessingFailure.class,()->cipher.decrypt(encrypted,"other".getBytes(StandardCharsets.UTF_8)));
        assertFalse(error.retryable()); assertEquals(ProcessingFailure.Code.PAYLOAD_AUTHENTICATION_FAILED,error.code());
    }
    @Test void missingKeyIsRecoverableAndNeverAttemptsAnotherKey() {
        var encrypted=cipher.encrypt(plaintext,aad);
        var error=assertThrows(ProcessingFailure.class,()->cipher.decrypt(new EncryptedPayload(encrypted.bytes(),"old"),aad));
        assertTrue(error.retryable()); assertEquals(ProcessingFailure.Code.KEY_UNAVAILABLE,error.code());
    }
    @Test void rejectsInvalidEnvelopeVersionsAndTruncation() {
        var encrypted=cipher.encrypt(plaintext,aad); byte[] altered=encrypted.bytes(); altered[0]=2;
        assertThrows(ProcessingFailure.class,()->cipher.decrypt(new EncryptedPayload(altered,"v1"),aad));
        assertThrows(ProcessingFailure.class,()->cipher.decrypt(new EncryptedPayload(new byte[20],"v1"),aad));
    }
}
