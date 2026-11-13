package co.finanzas.domain;

import co.finanzas.domain.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test
    void defaultsToCopAndNormalizesWithoutRounding() {
        Money money = new Money(new BigDecimal("45900.0"));
        assertEquals(Money.COP, money.currency());
        assertEquals(new BigDecimal("45900.00"), money.amount());
        assertEquals(money, new Money(new BigDecimal("45900.000")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "1.999", "10000000000000000.00"})
    void rejectsInvalidAmounts(String value) {
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal(value)));
    }

    @Test
    void preservesDecimalPrecision() {
        assertEquals(new BigDecimal("0.30"), new Money(new BigDecimal("0.10"))
                .plus(new Money(new BigDecimal("0.20"))).amount());
    }

    @Test
    void rejectsImplicitCurrencyConversionAndOverflow() {
        Money cop = new Money(BigDecimal.ONE);
        Money usd = new Money(BigDecimal.ONE, Currency.getInstance("USD"));
        assertThrows(IllegalArgumentException.class, () -> cop.plus(usd));
        assertThrows(IllegalArgumentException.class,
                () -> new Money(Money.MAX_AMOUNT).plus(cop));
    }

    @Test
    void requiresAmountAndCurrency() {
        assertThrows(NullPointerException.class, () -> new Money(null));
        assertThrows(NullPointerException.class, () -> new Money(BigDecimal.ONE, null));
    }
}
