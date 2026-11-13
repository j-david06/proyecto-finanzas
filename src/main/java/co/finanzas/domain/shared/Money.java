package co.finanzas.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/** Positive movement/commitment amount, not a signed account balance. */
public record Money(BigDecimal amount, Currency currency) {
    public static final Currency COP = Currency.getInstance("COP");
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999999.99");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (!currency.getCurrencyCode().matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must use a three-letter code");
        }
        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Amount must be positive and fit numeric(18,2)");
        }
        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount cannot have fractional cents", exception);
        }
    }

    public Money(BigDecimal amount) {
        this(amount, COP);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public void requireSameCurrency(Money other) {
        if (!currency.equals(Objects.requireNonNull(other, "other").currency)) {
            throw new IllegalArgumentException("Currency conversion must be explicit");
        }
    }
}
