package co.finanzas.domain;

import co.finanzas.domain.billing.BillingSnapshot;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class BillingSnapshotTest {
    private final LocalDate start=LocalDate.of(2026,9,14),cut=LocalDate.of(2026,10,15),due=LocalDate.of(2026,11,4);
    @Test void acceptsObservedMinimumIncludingZeroWithoutCreatingAMoneyMovement(){
        assertEquals(new BigDecimal("0.00"),new BillingSnapshot(BigDecimal.ZERO,start,cut,cut,due).minimumPayment());
    }
    @Test void rejectsInvalidAmountsAndContradictoryDates(){
        assertThrows(IllegalArgumentException.class,()->new BillingSnapshot(new BigDecimal("-1"),start,cut,cut,due));
        assertThrows(IllegalArgumentException.class,()->new BillingSnapshot(new BigDecimal("0.001"),start,cut,cut,due));
        assertThrows(IllegalArgumentException.class,()->new BillingSnapshot(BigDecimal.ONE,start,cut,cut.plusDays(1),due));
        assertThrows(IllegalArgumentException.class,()->new BillingSnapshot(BigDecimal.ONE,start,cut,cut,cut));
        assertThrows(IllegalArgumentException.class,()->new BillingSnapshot(BigDecimal.ONE,start.minusYears(1),cut,cut,due));
    }
}
