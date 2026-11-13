package co.finanzas.domain;

import co.finanzas.domain.account.Account;
import co.finanzas.domain.category.Category;
import co.finanzas.domain.recurring.BillingSchedule;
import co.finanzas.domain.recurring.RecurringCharge;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.OwnerId;
import co.finanzas.domain.transaction.Transaction;
import co.finanzas.domain.transaction.TransactionKind;
import co.finanzas.domain.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class DomainFixtures {
    static final OwnerId OWNER = new OwnerId(UUID.randomUUID());
    static final Money AMOUNT = new Money(new BigDecimal("45900.00"));
    static final Account ACCOUNT = new Account(UUID.randomUUID(), OWNER, "Nu", Account.Kind.DEPOSIT);
    static final Category EXPENSE = new Category(UUID.randomUUID(), OWNER, "Servicios", TransactionKind.EXPENSE, true);

    private DomainFixtures() { }

    static RecurringCharge subscription() {
        return new RecurringCharge(UUID.randomUUID(), OWNER, ACCOUNT, EXPENSE, "Servicio",
                RecurringCharge.Kind.SUBSCRIPTION, AMOUNT,
                new BillingSchedule(LocalDate.of(2026, 1, 1), null, 1, 31), null, true);
    }

    static Transaction movement(Account account, Account destination, Category category,
                                RecurringCharge charge, TransactionKind kind, Money amount,
                                LocalDate dueOn, Integer installment) {
        return new Transaction(UUID.randomUUID(), OWNER, account, destination, category, charge,
                kind, TransactionStatus.PENDING, amount, Instant.parse("2026-11-13T18:45:12Z"),
                "Comercio", null, "NU_CO", null, dueOn, installment);
    }
}
