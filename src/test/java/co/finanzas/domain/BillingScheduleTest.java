package co.finanzas.domain;

import co.finanzas.domain.recurring.BillingSchedule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class BillingScheduleTest {
    @Test
    void clampsFebruaryAndRestoresOriginalDayInMarch() {
        BillingSchedule schedule = new BillingSchedule(LocalDate.of(2026, 1, 1), null, 1, 31);
        assertEquals(LocalDate.of(2026, 1, 31), schedule.dueOn(1));
        assertEquals(LocalDate.of(2026, 2, 28), schedule.dueOn(2));
        assertEquals(LocalDate.of(2026, 3, 31), schedule.dueOn(3));
        assertFalse(schedule.contains(LocalDate.of(2026, 3, 28)));
    }

    @Test
    void supportsLeapYears() {
        BillingSchedule schedule = new BillingSchedule(LocalDate.of(2028, 1, 1), null, 1, 31);
        assertEquals(LocalDate.of(2028, 2, 29), schedule.dueOn(2));
    }

    @Test
    void startsAtNextEligibleBillingDayAndKeepsCadence() {
        BillingSchedule schedule = new BillingSchedule(LocalDate.of(2026, 1, 20), null, 2, 15);
        assertEquals(LocalDate.of(2026, 2, 15), schedule.firstDueOn());
        assertEquals(LocalDate.of(2026, 4, 15), schedule.dueOn(2));
        assertEquals(LocalDate.of(2026, 4, 15), schedule.nextDueOnOrAfter(LocalDate.of(2026, 3, 1)).orElseThrow());
        assertEquals(LocalDate.of(2026, 2, 15), schedule.nextDueOnOrAfter(LocalDate.of(2025, 1, 1)).orElseThrow());
    }

    @Test
    void respectsInclusiveEndAndRejectsInvalidDates() {
        BillingSchedule schedule = new BillingSchedule(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), 1, 31);
        assertTrue(schedule.contains(LocalDate.of(2026, 2, 28)));
        assertTrue(schedule.nextDueOnOrAfter(LocalDate.of(2026, 3, 1)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> schedule.dueOn(3));
        assertThrows(IllegalArgumentException.class, () -> schedule.dueOn(0));
        assertThrows(IllegalArgumentException.class,
                () -> new BillingSchedule(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 21), 1, 15));
        assertThrows(IllegalArgumentException.class,
                () -> new BillingSchedule(LocalDate.of(2026, 1, 1), null, 0, 15));
        assertThrows(IllegalArgumentException.class,
                () -> new BillingSchedule(LocalDate.of(2026, 1, 1), null, 1, 32));
    }
}
