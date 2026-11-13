package co.finanzas.application.processing.model;

import co.finanzas.application.ingestion.model.EncryptedPayload;
import co.finanzas.domain.shared.OwnerId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public record IngestionWork(UUID id, OwnerId owner, UUID connectionId, String eventId,
                            EncryptedPayload payload, byte[] hash, UUID leaseToken, int attempts,String source) {
    public IngestionWork { hash = hash.clone(); }
    @Override public byte[] hash() { return hash.clone(); }
    public byte[] associatedData() {
        return (owner.value() + ":" + connectionId + ":" + eventId).getBytes(StandardCharsets.UTF_8);
    }
}
