package co.finanzas.application.ingestion.model;

import co.finanzas.domain.shared.OwnerId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record SourceConnection(UUID id, OwnerId ownerId, String source, String authenticationProfile,
                               byte[] apiKeyHash, Set<UUID> allowedAccounts, boolean active, Instant expiresAt) {
    public SourceConnection {
        apiKeyHash = apiKeyHash == null ? new byte[0] : apiKeyHash.clone();
        allowedAccounts = Set.copyOf(allowedAccounts);
    }
    @Override public byte[] apiKeyHash() { return apiKeyHash.clone(); }
}
