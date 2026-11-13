package co.finanzas.ingestion;

import co.finanzas.adapters.out.parser.ColombianPesoParser;
import co.finanzas.adapters.out.parser.MessageTimestampParser;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import static org.junit.jupiter.api.Assertions.*;

class MessageValueParsersTest {
    private final ColombianPesoParser amounts = new ColombianPesoParser();
    private final MessageTimestampParser dates = new MessageTimestampParser();

    @Test void parsesExplicitCopWithExactCentsAndWellFormedGrouping() {
        for (String input : new String[]{"COP $ 45.900,50", "$45,900.50 COP", "COP 45900.50", "COP 45900,50",
                "COP\u00a0$\u202f45.900,50"}) {
            assertEquals(new BigDecimal("45900.50"), amounts.parse(input).amount(), input);
        }
        assertEquals(new BigDecimal("9999999999999999.99"), amounts.parse("COP 9.999.999.999.999.999,99").amount());
        assertEquals(new BigDecimal("45900.00"), amounts.parse("COP 45.900").amount());
    }

    @Test void rejectsUnknownCurrencyMalformedGroupingPrecisionAndAdditionalAmounts() {
        for (String input : new String[]{"$ 45.900,50", "USD 100.00", "COP -1", "COP +1", "COP 0", "COP 01",
                "COP 1e3", "COP NaN", "COP 12.34.567,89", "COP 1,234,56", "COP 1234.567", "COP 1.2",
                "COP 1.234,567", "COP 10 COP 20", "COP 1 000", "COP 10000000000000000", "COP 1.00\nextra"}) {
            assertThrows(IllegalArgumentException.class, () -> amounts.parse(input), input);
        }
        assertThrows(IllegalArgumentException.class, () -> amounts.parse(null));
        assertThrows(IllegalArgumentException.class, () -> amounts.parse("COP " + "1".repeat(65)));
    }

    @Test void parsesOnlyCompleteMovementTimesWithAnExplicitZoneContract() {
        Instant expected = Instant.parse("2026-11-13T17:05:06Z");
        assertEquals(expected, dates.parseOffset("2026-11-13T12:05:06-05:00"));
        assertEquals(expected, dates.parseBogota("13/11/2026 12:05:06"));
        assertThrows(DateTimeParseException.class, () -> dates.parseOffset("2026-11-13T12:05:06"));
        assertThrows(DateTimeParseException.class, () -> dates.parseBogota("31/02/2026 12:05:06"));
        assertThrows(IllegalArgumentException.class, () -> dates.parseBogota("13/11/2026"));
    }
}
