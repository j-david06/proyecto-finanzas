package co.finanzas.application.ingestion.port.in;

import co.finanzas.application.ingestion.model.AuthenticatedSource;
import co.finanzas.application.ingestion.model.IngestionReceipt;
import co.finanzas.domain.shared.Money;
import java.util.UUID;

public interface ReceiveIngestionUseCase {
    IngestionReceipt receive(AuthenticatedSource source, UUID eventId, UUID accountId, Money amount, byte[] rawBody);
    IngestionReceipt status(AuthenticatedSource source, UUID ingestionId);
}
