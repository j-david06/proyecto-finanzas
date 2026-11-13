package co.finanzas.domain;

import co.finanzas.domain.account.Account;
import co.finanzas.domain.category.Category;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.OwnerId;
import co.finanzas.domain.transaction.Transaction;
import co.finanzas.domain.transaction.TransactionKind;
import co.finanzas.domain.transaction.TransactionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

import static co.finanzas.domain.DomainFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {
    @Test
    void postsPendingOnceWithoutMutatingOriginal() {
        Transaction pending = expense(ACCOUNT, EXPENSE);
        Transaction posted = pending.post();
        assertEquals(TransactionStatus.PENDING, pending.status());
        assertEquals(TransactionStatus.POSTED, posted.status());
        assertEquals(pending.id(), posted.id());
        assertThrows(IllegalStateException.class, posted::post);
        assertThrows(IllegalStateException.class, posted::voidPending);
    }

    @Test
    void voidedMovementCannotBeRegisteredOrPosted() {
        Transaction voided = expense(ACCOUNT, EXPENSE).voidPending();
        assertEquals(TransactionStatus.VOIDED, voided.status());
        assertThrows(IllegalStateException.class, voided::requireRegistrable);
        assertThrows(IllegalStateException.class, voided::post);
    }

    @Test
    void transferRequiresDistinctSameOwnerSameCurrencyDestinationAndNoCategory() {
        Account cash = new Account(UUID.randomUUID(), OWNER, "Efectivo", Account.Kind.CASH);
        assertDoesNotThrow(() -> transfer(cash, null));
        assertThrows(IllegalArgumentException.class, () -> transfer(null, null));
        assertThrows(IllegalArgumentException.class, () -> transfer(ACCOUNT, null));
        assertThrows(IllegalArgumentException.class, () -> transfer(cash, EXPENSE));
        Account foreign = new Account(UUID.randomUUID(), new OwnerId(UUID.randomUUID()), "Ajena", Account.Kind.CASH);
        assertThrows(IllegalArgumentException.class, () -> transfer(foreign, null));
        Account usd = new Account(UUID.randomUUID(), OWNER, "USD", Account.Kind.DEPOSIT, Currency.getInstance("USD"), true);
        assertThrows(IllegalArgumentException.class, () -> transfer(usd, null));
    }

    @Test
    void rejectsCrossOwnerAndWrongKindCategories() {
        Account foreign = new Account(UUID.randomUUID(), new OwnerId(UUID.randomUUID()), "Ajena", Account.Kind.CASH);
        assertThrows(IllegalArgumentException.class, () -> expense(foreign, null));
        Category income = new Category(UUID.randomUUID(), OWNER, "Ingreso", TransactionKind.INCOME, true);
        assertThrows(IllegalArgumentException.class, () -> expense(ACCOUNT, income));
        Category foreignCategory = new Category(UUID.randomUUID(), foreign.ownerId(), "Otra", TransactionKind.EXPENSE, true);
        assertThrows(IllegalArgumentException.class, () -> expense(ACCOUNT, foreignCategory));
        assertThrows(IllegalArgumentException.class, () -> new Category(UUID.randomUUID(), OWNER, "Inválida", TransactionKind.TRANSFER, true));
    }

    @Test
    void rejectsMismatchedAmountCurrencyAndOrphanRecurrenceMetadata() {
        assertThrows(IllegalArgumentException.class, () -> movement(ACCOUNT, null, null, null,
                TransactionKind.EXPENSE, new Money(BigDecimal.ONE, Currency.getInstance("USD")), null, null));
        assertThrows(IllegalArgumentException.class, () -> movement(ACCOUNT, null, null, null,
                TransactionKind.EXPENSE, AMOUNT, LocalDate.of(2026, 1, 31), null));
    }

    @Test
    void preservesHistoricalInactiveResourcesButRejectsNewRegistration() {
        Transaction historical = expense(ACCOUNT.deactivate(), EXPENSE);
        assertThrows(IllegalStateException.class, historical::requireRegistrable);
        Category inactive = new Category(EXPENSE.id(), OWNER, EXPENSE.name(), EXPENSE.kind(), false);
        assertThrows(IllegalStateException.class, () -> expense(ACCOUNT, inactive).requireRegistrable());
    }

    @Test
    void recurringChargeRequiresCorrectOccurrenceAndAccount() {
        var subscription = subscription();
        assertDoesNotThrow(() -> movement(ACCOUNT, null, EXPENSE, subscription,
                TransactionKind.EXPENSE, AMOUNT, LocalDate.of(2026, 1, 31), null));
        Account other = new Account(UUID.randomUUID(), OWNER, "Otra", Account.Kind.DEPOSIT);
        assertThrows(IllegalArgumentException.class, () -> movement(other, null, EXPENSE, subscription,
                TransactionKind.EXPENSE, AMOUNT, LocalDate.of(2026, 1, 31), null));
        assertThrows(IllegalArgumentException.class, () -> movement(ACCOUNT, null, EXPENSE, subscription,
                TransactionKind.EXPENSE, AMOUNT, null, null));
    }

    private Transaction expense(Account account, Category category) {
        return movement(account, null, category, null, TransactionKind.EXPENSE, AMOUNT, null, null);
    }

    private Transaction transfer(Account destination, Category category) {
        return movement(ACCOUNT, destination, category, null, TransactionKind.TRANSFER, AMOUNT, null, null);
    }
}
