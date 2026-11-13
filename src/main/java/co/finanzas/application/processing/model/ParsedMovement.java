package co.finanzas.application.processing.model;

import co.finanzas.domain.shared.Money;
import co.finanzas.domain.transaction.TransactionKind;
import java.time.Instant;
import java.util.UUID;

public record ParsedMovement(UUID accountId, TransactionKind kind, Money amount, Instant occurredAt,
                             String merchant, String bankProvider, String bankEventId, String parserVersion) { }
