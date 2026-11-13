package co.finanzas.application.ingestion.model;

import co.finanzas.domain.shared.OwnerId;
import java.util.Set;
import java.util.UUID;

public record AuthenticatedSource(UUID connectionId, OwnerId ownerId, byte[] apiKeyHash, Set<UUID> allowedAccounts) {
    public AuthenticatedSource {
        apiKeyHash = apiKeyHash.clone();
        allowedAccounts = Set.copyOf(allowedAccounts);
    }
    @Override public byte[] apiKeyHash() { return apiKeyHash.clone(); }
}
