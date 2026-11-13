package co.finanzas.application.processing.model;

import co.finanzas.domain.shared.Money;
import co.finanzas.domain.transaction.TransactionKind;
import co.finanzas.domain.transaction.TransactionStatus;
import co.finanzas.domain.transaction.TransactionMetadata;
import java.time.Instant;
import java.util.UUID;

public record ParsedMovement(UUID accountId, TransactionKind kind, Money amount, Instant occurredAt,
                             String merchant, String bankProvider, String bankEventId, String parserVersion,
                             UUID destinationAccountId, TransactionStatus status, TransactionMetadata metadata) {
    public ParsedMovement(UUID accountId, TransactionKind kind, Money amount, Instant occurredAt,
                          String merchant, String bankProvider, String bankEventId, String parserVersion) {
        this(accountId,kind,amount,occurredAt,merchant,bankProvider,bankEventId,parserVersion,null,
                TransactionStatus.POSTED,TransactionMetadata.EMPTY);
    }
}
