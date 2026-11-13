package co.finanzas.application.ingestion.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Extension point only. No implementation, nonce store or HMAC activation in phase 1. */
public interface HmacVerificationPort {
    boolean verify(SignatureInput input);

    record SignatureInput(UUID sourceConnectionId, String method, String path,
                          Instant timestamp, UUID nonce, String idempotencyKey,
                          byte[] rawBody, String signature) {
        public SignatureInput {
            Objects.requireNonNull(sourceConnectionId, "sourceConnectionId");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            rawBody = Objects.requireNonNull(rawBody, "rawBody").clone();
            Objects.requireNonNull(signature, "signature");
        }

        @Override
        public byte[] rawBody() {
            return rawBody.clone();
        }
    }
}
