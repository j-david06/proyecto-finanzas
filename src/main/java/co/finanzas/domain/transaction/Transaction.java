package co.finanzas.domain.transaction;

import co.finanzas.domain.account.Account;
import co.finanzas.domain.category.Category;
import co.finanzas.domain.recurring.RecurringCharge;
import co.finanzas.domain.shared.DomainChecks;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.OwnerId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable aggregate snapshots. Inactive resources remain valid for historical reconstruction. */
public record Transaction(UUID id, OwnerId ownerId, Account account, Account counterpartyAccount,
                          Category category, RecurringCharge recurringCharge,
                          TransactionKind kind, TransactionStatus status, Money amount,
                          Instant occurredAt, String merchant, String description,
                          String bankProvider, String bankEventId,
                          LocalDate recurringDueOn, Integer installmentNumber, TransactionMetadata metadata) {
    public Transaction {
        Objects.requireNonNull(id, "transactionId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.tax4x1000() != null && (!amount.currency().equals(Money.COP)
                || metadata.tax4x1000().compareTo(amount.amount()) > 0)) throw new IllegalArgumentException("Tax must fit the COP total");
        account.requireCompatible(ownerId, amount);
        merchant = DomainChecks.optionalText(merchant, "merchant", 200);
        description = DomainChecks.optionalText(description, "description", 500);
        bankProvider = DomainChecks.optionalText(bankProvider, "bankProvider", 40);
        bankEventId = DomainChecks.optionalText(bankEventId, "bankEventId", 200);
        if (bankEventId != null && bankProvider == null) {
            throw new IllegalArgumentException("Bank event IDs require a provider");
        }
        if (kind == TransactionKind.TRANSFER) {
            if (counterpartyAccount == null || counterpartyAccount.id().equals(account.id()) || category != null) {
                throw new IllegalArgumentException("Transfers require a distinct destination and no category");
            }
            counterpartyAccount.requireCompatible(ownerId, amount);
        } else if (counterpartyAccount != null) {
            throw new IllegalArgumentException("Only transfers have a destination account");
        }
        if (category != null) {
            category.requireCompatible(ownerId, kind);
        }
        if (recurringCharge == null) {
            if (recurringDueOn != null || installmentNumber != null) {
                throw new IllegalArgumentException("Recurrence metadata requires a recurring charge");
            }
        } else {
            DomainChecks.sameOwner(ownerId, recurringCharge.ownerId());
            if (!account.id().equals(recurringCharge.account().id())) {
                throw new IllegalArgumentException("Recurring charge belongs to a different account");
            }
            amount.requireSameCurrency(recurringCharge.expectedAmount());
            if (kind == TransactionKind.INCOME
                    || (kind == TransactionKind.TRANSFER && recurringCharge.kind() != RecurringCharge.Kind.INSTALLMENT)) {
                throw new IllegalArgumentException("Unsupported movement for this recurring charge");
            }
            recurringCharge.requireOccurrence(recurringDueOn, installmentNumber);
        }
    }

    public Transaction(UUID id, OwnerId ownerId, Account account, Account counterpartyAccount,
                       Category category, RecurringCharge recurringCharge, TransactionKind kind,
                       TransactionStatus status, Money amount, Instant occurredAt, String merchant,
                       String description, String bankProvider, String bankEventId,
                       LocalDate recurringDueOn, Integer installmentNumber) {
        this(id,ownerId,account,counterpartyAccount,category,recurringCharge,kind,status,amount,occurredAt,
                merchant,description,bankProvider,bankEventId,recurringDueOn,installmentNumber,TransactionMetadata.EMPTY);
    }

    /** Import an explicit cancelled observation without pretending that it settled. */
    public void requireIngestible() {
        if (status == TransactionStatus.PENDING) throw new IllegalStateException("Ingestion needs an explicit final state");
        requireActiveResources();
    }

    /** Command validation; call before inserting a newly registered transaction. */
    public void requireRegistrable() {
        if (status == TransactionStatus.VOIDED) {
            throw new IllegalStateException("Cannot register an already voided transaction");
        }
        requireActiveResources();
    }

    private void requireActiveResources() {
        account.requireActive();
        if (counterpartyAccount != null) {
            counterpartyAccount.requireActive();
        }
        if (category != null) {
            category.requireActive();
        }
        if (recurringCharge != null) {
            recurringCharge.requireRegistrable();
        }
    }

    public Transaction post() {
        requirePending();
        return withStatus(TransactionStatus.POSTED);
    }

    public Transaction voidPending() {
        requirePending();
        return withStatus(TransactionStatus.VOIDED);
    }

    private void requirePending() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException("Only pending transactions can change status");
        }
    }

    private Transaction withStatus(TransactionStatus nextStatus) {
        return new Transaction(id, ownerId, account, counterpartyAccount, category, recurringCharge,
                kind, nextStatus, amount, occurredAt, merchant, description,
                bankProvider, bankEventId, recurringDueOn, installmentNumber, metadata);
    }
}
