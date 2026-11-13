package co.finanzas.application.processing.port.out;

import co.finanzas.application.processing.model.*;
import co.finanzas.domain.account.Account;
import co.finanzas.domain.transaction.Transaction;
import java.util.Optional;
import java.util.UUID;

public interface ProcessingRepository {
    Optional<IngestionWork> claim();
    Account account(IngestionWork work, UUID accountId);
    boolean complete(IngestionWork work, Transaction transaction, String parserVersion);
    boolean fail(IngestionWork work, ProcessingFailure failure);
}
