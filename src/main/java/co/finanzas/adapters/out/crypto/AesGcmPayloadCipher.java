package co.finanzas.adapters.out.crypto;

import co.finanzas.application.ingestion.model.EncryptedPayload;
import co.finanzas.application.ingestion.port.out.PayloadCipherPort;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class AesGcmPayloadCipher implements PayloadCipherPort {
    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public AesGcmPayloadCipher(String base64Key, String keyId) {
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(base64Key); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("Invalid INGESTION_PAYLOAD_KEY_BASE64"); }
        if (decoded.length != 32 || keyId == null || !keyId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalStateException("Configure a 32-byte INGESTION_PAYLOAD_KEY_BASE64 and valid key ID");
        }
        key = new SecretKeySpec(decoded, "AES");
        java.util.Arrays.fill(decoded, (byte) 0);
        this.keyId = keyId;
    }

    @Override public EncryptedPayload encrypt(byte[] plaintext, byte[] associatedData) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData);
            byte[] encrypted = cipher.doFinal(plaintext);
            return new EncryptedPayload(ByteBuffer.allocate(1 + nonce.length + encrypted.length)
                    .put((byte) 1).put(nonce).put(encrypted).array(), keyId);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Payload encryption failed", exception);
        }
    }
}
