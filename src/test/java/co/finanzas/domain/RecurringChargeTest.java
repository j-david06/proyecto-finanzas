package co.finanzas.domain;

import co.finanzas.domain.account.Account;
import co.finanzas.domain.recurring.BillingSchedule;
import co.finanzas.domain.recurring.RecurringCharge;
import co.finanzas.domain.shared.OwnerId;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static co.finanzas.domain.DomainFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RecurringChargeTest {
    private final BillingSchedule schedule = new BillingSchedule(LocalDate.of(2026, 1, 1), null, 1, 31);

    @Test
    void requiresPositiveFiniteCountOnlyForInstallments() {
        for (Integer count : new Integer[]{null, 0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> charge(RecurringCharge.Kind.INSTALLMENT, count, true));
        }
        assertThrows(IllegalArgumentException.class, () -> charge(RecurringCharge.Kind.SUBSCRIPTION, 12, true));
        assertDoesNotThrow(() -> charge(RecurringCharge.Kind.SUBSCRIPTION, null, true));
    }

    @Test
    void boundsInstallmentsAndMatchesNumberWithDueDate() {
        RecurringCharge charge = charge(RecurringCharge.Kind.INSTALLMENT, 2, true);
        assertDoesNotThrow(() -> charge.requireOccurrence(LocalDate.of(2026, 2, 28), 2));
        assertThrows(IllegalArgumentException.class, () -> charge.requireOccurrence(LocalDate.of(2026, 2, 28), 1));
        assertThrows(IllegalArgumentException.class, () -> charge.requireOccurrence(LocalDate.of(2026, 3, 31), 3));
        assertTrue(charge.nextDueOnOrAfter(LocalDate.of(2026, 3, 1)).isEmpty());
    }

    @Test
    void rejectsScheduleEndingBeforeLastInstallment() {
        assertThrows(IllegalArgumentException.class, () -> new RecurringCharge(UUID.randomUUID(), OWNER,
                ACCOUNT, EXPENSE, "Cuotas", RecurringCharge.Kind.INSTALLMENT, AMOUNT,
                new BillingSchedule(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 1, 31), 2, true));
    }

    @Test
    void validatesSubscriptionMetadataAndActiveState() {
        RecurringCharge subscription = subscription();
        assertThrows(IllegalArgumentException.class,
                () -> subscription.requireOccurrence(LocalDate.of(2026, 1, 31), 1));
        assertThrows(IllegalArgumentException.class,
                () -> subscription.requireOccurrence(LocalDate.of(2026, 1, 30), null));
        RecurringCharge inactive = charge(RecurringCharge.Kind.SUBSCRIPTION, null, false);
        assertTrue(inactive.nextDueOnOrAfter(LocalDate.of(2026, 1, 1)).isEmpty());
        assertThrows(IllegalStateException.class, inactive::requireRegistrable);
    }

    @Test
    void rejectsCrossOwnerAccount() {
        Account foreign = new Account(UUID.randomUUID(), new OwnerId(UUID.randomUUID()), "Otra", Account.Kind.CASH);
        assertThrows(IllegalArgumentException.class, () -> new RecurringCharge(UUID.randomUUID(), OWNER,
                foreign, null, "Cargo", RecurringCharge.Kind.SUBSCRIPTION, AMOUNT, schedule, null, true));
    }

    private RecurringCharge charge(RecurringCharge.Kind kind, Integer count, boolean active) {
        return new RecurringCharge(UUID.randomUUID(), OWNER, ACCOUNT, EXPENSE, "Cargo", kind,
                AMOUNT, schedule, count, active);
    }
}
