package co.finanzas.domain.billing;

import co.finanzas.domain.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Observed COP statement values; neither an expense nor a promise that a bill has been paid. */
public record BillingSnapshot(BigDecimal minimumPayment,LocalDate periodStart,LocalDate periodEnd,
                               LocalDate cutOffDate,LocalDate paymentDueDate) {
    public BillingSnapshot {
        Objects.requireNonNull(minimumPayment);Objects.requireNonNull(periodStart);Objects.requireNonNull(periodEnd);
        Objects.requireNonNull(cutOffDate);Objects.requireNonNull(paymentDueDate);
        if(minimumPayment.signum()<0 || minimumPayment.compareTo(Money.MAX_AMOUNT)>0)throw new IllegalArgumentException("Invalid minimum payment");
        try { minimumPayment=minimumPayment.setScale(2,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException exception){throw new IllegalArgumentException("Invalid payment precision");}
        long period=ChronoUnit.DAYS.between(periodStart,periodEnd),due=ChronoUnit.DAYS.between(cutOffDate,paymentDueDate);
        if(period<1 || period>62 || !periodEnd.equals(cutOffDate) || due<1 || due>62)
            throw new IllegalArgumentException("Inconsistent monthly statement dates");
    }
}
