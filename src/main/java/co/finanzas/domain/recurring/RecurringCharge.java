package co.finanzas.domain.recurring;

import co.finanzas.domain.account.Account;
import co.finanzas.domain.category.Category;
import co.finanzas.domain.shared.DomainChecks;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.OwnerId;
import co.finanzas.domain.transaction.TransactionKind;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Forecast only: creating or advancing a charge never creates a financial transaction. */
public record RecurringCharge(UUID id, OwnerId ownerId, Account account, Category category,
                              String name, Kind kind, Money expectedAmount,
                              BillingSchedule schedule, Integer totalInstallments, boolean active) {
    public enum Kind { SUBSCRIPTION, INSTALLMENT }

    public RecurringCharge {
        Objects.requireNonNull(id, "recurringChargeId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(account, "account");
        name = DomainChecks.requiredText(name, "name", 160);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expectedAmount, "expectedAmount");
        Objects.requireNonNull(schedule, "schedule");
        account.requireCompatible(ownerId, expectedAmount);
        if (category != null) {
            category.requireCompatible(ownerId, TransactionKind.EXPENSE);
        }
        if (kind == Kind.SUBSCRIPTION && totalInstallments != null) {
            throw new IllegalArgumentException("Subscriptions have no installment count");
        }
        if (kind == Kind.INSTALLMENT) {
            if (totalInstallments == null || totalInstallments < 1) {
                throw new IllegalArgumentException("Installments require a positive count");
            }
            schedule.dueOn(totalInstallments);
        }
    }

    public void requireRegistrable() {
        if (!active) {
            throw new IllegalStateException("Recurring charge is inactive");
        }
        account.requireActive();
        if (category != null) {
            category.requireActive();
        }
    }

    public Optional<LocalDate> nextDueOnOrAfter(LocalDate date) {
        if (!active) {
            return Optional.empty();
        }
        return schedule.nextDueOnOrAfter(date)
                .filter(due -> totalInstallments == null || !due.isAfter(schedule.dueOn(totalInstallments)));
    }

    public void requireOccurrence(LocalDate dueOn, Integer installmentNumber) {
        if (!schedule.contains(dueOn)) {
            throw new IllegalArgumentException("Due date does not belong to this schedule");
        }
        if (kind == Kind.SUBSCRIPTION) {
            if (installmentNumber != null) {
                throw new IllegalArgumentException("Subscriptions have no installment number");
            }
        } else if (installmentNumber == null || installmentNumber < 1
                || installmentNumber > totalInstallments
                || !schedule.dueOn(installmentNumber).equals(dueOn)) {
            throw new IllegalArgumentException("Installment number and due date do not match");
        }
    }
}
