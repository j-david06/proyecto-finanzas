package co.finanzas.domain.recurring;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/** Monthly cadence anchored to the original billing day, including after February. */
public record BillingSchedule(LocalDate startsOn, LocalDate endsOn,
                              int intervalMonths, int billingDay) {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Bogota");

    public BillingSchedule {
        Objects.requireNonNull(startsOn, "startsOn");
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("endsOn cannot precede startsOn");
        }
        if (intervalMonths < 1 || intervalMonths > Short.MAX_VALUE) {
            throw new IllegalArgumentException("intervalMonths must be 1..32767");
        }
        if (billingDay < 1 || billingDay > 31) {
            throw new IllegalArgumentException("billingDay must be 1..31");
        }
        LocalDate first = firstDue(startsOn, billingDay);
        if (endsOn != null && first.isAfter(endsOn)) {
            throw new IllegalArgumentException("Schedule must contain at least one due date");
        }
    }

    public LocalDate dueOn(int occurrenceNumber) {
        if (occurrenceNumber < 1) {
            throw new IllegalArgumentException("Occurrence numbers start at 1");
        }
        YearMonth month = YearMonth.from(firstDueOn())
                .plusMonths(Math.multiplyExact((long) occurrenceNumber - 1, intervalMonths));
        LocalDate due = atBillingDay(month, billingDay);
        if (endsOn != null && due.isAfter(endsOn)) {
            throw new IllegalArgumentException("Occurrence is beyond endsOn");
        }
        return due;
    }

    public LocalDate firstDueOn() {
        return firstDue(startsOn, billingDay);
    }

    public Optional<LocalDate> nextDueOnOrAfter(LocalDate date) {
        Objects.requireNonNull(date, "date");
        YearMonth firstMonth = YearMonth.from(firstDueOn());
        long months = Math.max(0, ChronoUnit.MONTHS.between(firstMonth, YearMonth.from(date)));
        long offset = months / intervalMonths * intervalMonths;
        LocalDate candidate = atBillingDay(firstMonth.plusMonths(offset), billingDay);
        if (candidate.isBefore(date)) {
            candidate = atBillingDay(firstMonth.plusMonths(offset + intervalMonths), billingDay);
        }
        return endsOn != null && candidate.isAfter(endsOn) ? Optional.empty() : Optional.of(candidate);
    }

    public boolean contains(LocalDate date) {
        return date != null && nextDueOnOrAfter(date).filter(date::equals).isPresent();
    }

    private static LocalDate firstDue(LocalDate startsOn, int billingDay) {
        YearMonth month = YearMonth.from(startsOn);
        LocalDate candidate = atBillingDay(month, billingDay);
        return candidate.isBefore(startsOn) ? atBillingDay(month.plusMonths(1), billingDay) : candidate;
    }

    private static LocalDate atBillingDay(YearMonth month, int billingDay) {
        return month.atDay(Math.min(billingDay, month.lengthOfMonth()));
    }
}
