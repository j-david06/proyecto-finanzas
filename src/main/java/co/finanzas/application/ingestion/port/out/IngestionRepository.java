package co.finanzas.application.ingestion.port.out;

import co.finanzas.application.ingestion.model.AuthenticatedSource;
import co.finanzas.application.ingestion.model.EncryptedPayload;
import co.finanzas.application.ingestion.model.IngestionReceipt;
import co.finanzas.domain.shared.Money;
import java.util.Optional;
import java.util.UUID;

public interface IngestionRepository {
    IngestionReceipt accept(AuthenticatedSource source, UUID eventId, UUID accountId,
                            Money amount, byte[] payloadHash, EncryptedPayload payload);
    Optional<IngestionReceipt> find(AuthenticatedSource source, UUID ingestionId);
}
