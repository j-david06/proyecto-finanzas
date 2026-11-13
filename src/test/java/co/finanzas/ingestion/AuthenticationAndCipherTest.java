package co.finanzas.ingestion;

import co.finanzas.adapters.out.crypto.AesGcmPayloadCipher;
import co.finanzas.adapters.out.crypto.Sha256Digests;
import co.finanzas.application.ingestion.model.IngestionProblem;
import co.finanzas.application.ingestion.model.SourceConnection;
import co.finanzas.application.ingestion.service.ApiKeyAuthenticationService;
import co.finanzas.domain.shared.OwnerId;
import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationAndCipherTest {
    private final UUID id = UUID.randomUUID();
    private final OwnerId owner = new OwnerId(UUID.randomUUID());
    private final String key = "pf_ing_" + id + "." + "a".repeat(43);
    private final Sha256Digests digests = new Sha256Digests();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

    @Test void authenticatesAndRejectsWrongUnknownAndMalformedKeys() {
        var connection = connection(true, null, "IOS_SHORTCUTS", "API_KEY_ONLY");
        var service = new ApiKeyAuthenticationService(ignored -> Optional.of(connection), digests, clock);
        assertEquals(owner, service.authenticate(key).ownerId());
        assertThrows(IngestionProblem.class, () -> service.authenticate(key.substring(0, key.length() - 1) + "b"));
        assertThrows(IngestionProblem.class, () -> service.authenticate("invalid"));
        assertThrows(IngestionProblem.class, () -> service.authenticate(null));
        var unknown = new ApiKeyAuthenticationService(ignored -> Optional.empty(), digests, clock);
        assertThrows(IngestionProblem.class, () -> unknown.authenticate(key));
    }

    @Test void rejectsRevokedExpiredWrongSourceAndHmacWithoutDowngrade() {
        for (var connection : List.of(connection(false, null, "IOS_SHORTCUTS", "API_KEY_ONLY"),
                connection(true, clock.instant(), "IOS_SHORTCUTS", "API_KEY_ONLY"),
                connection(true, null, "GMAIL", "API_KEY_ONLY"),
                connection(true, null, "IOS_SHORTCUTS", "HMAC_REQUIRED"))) {
            var service = new ApiKeyAuthenticationService(ignored -> Optional.of(connection), digests, clock);
            assertThrows(IngestionProblem.class, () -> service.authenticate(key));
        }
    }

    @Test void encryptsWithFreshNonceAndBindsOwnerConnectionAndEventAsAad() throws Exception {
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        var cipher = new AesGcmPayloadCipher(Base64.getEncoder().encodeToString(secret), "test-v1");
        byte[] plain = "financial message".getBytes(StandardCharsets.UTF_8);
        byte[] aad = (owner.value() + ":" + id + ":event").getBytes(StandardCharsets.UTF_8);
        var first = cipher.encrypt(plain, aad);
        var second = cipher.encrypt(plain, aad);
        assertFalse(Arrays.equals(first.bytes(), second.bytes()));
        ByteBuffer envelope = ByteBuffer.wrap(first.bytes());
        assertEquals(1, envelope.get());
        byte[] nonce = new byte[12];
        envelope.get(nonce);
        byte[] encrypted = new byte[envelope.remaining()];
        envelope.get(encrypted);
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, nonce));
        decrypt.updateAAD(aad);
        assertArrayEquals(plain, decrypt.doFinal(encrypted));
        decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new GCMParameterSpec(128, nonce));
        decrypt.updateAAD("different-owner".getBytes(StandardCharsets.UTF_8));
        assertThrows(javax.crypto.AEADBadTagException.class, () -> decrypt.doFinal(encrypted));
    }

    @Test void requiresValidEncryptionKeyAndDefensivelyCopiesCiphertext() {
        assertThrows(IllegalStateException.class, () -> new AesGcmPayloadCipher("", "v1"));
        assertThrows(IllegalStateException.class, () -> new AesGcmPayloadCipher("not-base64", "v1"));
        var cipher = new AesGcmPayloadCipher(Base64.getEncoder().encodeToString(new byte[32]), "v1");
        var envelope = cipher.encrypt(new byte[]{1, 2, 3}, new byte[0]);
        byte[] copy = envelope.bytes();
        copy[0] = 99;
        assertEquals(1, envelope.bytes()[0]);
    }

    private SourceConnection connection(boolean active, Instant expires, String source, String profile) {
        return new SourceConnection(id, owner, source, profile, digests.sha256(key.getBytes(StandardCharsets.UTF_8)),
                Set.of(UUID.randomUUID()), active, expires);
    }
}
