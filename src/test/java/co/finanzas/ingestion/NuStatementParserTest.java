package co.finanzas.ingestion;

import co.finanzas.adapters.out.parser.NuStatementParser;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class NuStatementParserTest {
    @Test void derivesStatementYearFromReceiptAndPreservesMinimumPayment(){
        var b=new NuStatementParser().parse(NuMessageParserTest.sample("statement"),Instant.parse("2026-10-15T18:00:00Z"));
        assertEquals(new BigDecimal("190000.00"),b.minimumPayment());assertEquals("2026-09-14",b.periodStart().toString());
        assertEquals("2026-10-15",b.cutOffDate().toString());assertEquals("2026-11-04",b.paymentDueDate().toString());
    }
    @Test void decemberCutAndJanuaryDueDoNotUseTheSameYear(){
        String text=NuMessageParserTest.sample("statement").replace("14 septiembre - 15 octubre","14 noviembre - 15 diciembre")
                .replace("15 octubre","15 diciembre").replace("\n4 noviembre","\n4 enero");
        var b=new NuStatementParser().parse(text,Instant.parse("2027-01-01T18:00:00Z"));
        assertEquals("2026-12-15",b.cutOffDate().toString());assertEquals("2027-01-04",b.paymentDueDate().toString());
    }
    @Test void januaryPeriodCanStartInDecemberAndZeroMinimumIsValid(){
        String text=NuMessageParserTest.sample("statement").replace("14 septiembre - 15 octubre","14 diciembre - 15 enero")
                .replace("15 octubre","15 enero").replace("4 noviembre","4 febrero").replace("$190.000,00","$0,00");
        var b=new NuStatementParser().parse(text,Instant.parse("2027-01-15T18:00:00Z"));
        assertEquals("2026-12-14",b.periodStart().toString());assertEquals("2027-01-15",b.periodEnd().toString());
        assertEquals(new BigDecimal("0.00"),b.minimumPayment());
    }
    @Test void refusesStaleYearInferenceDuplicateLabelsAndContradictoryPeriods(){
        String text=NuMessageParserTest.sample("statement");
        assertThrows(IllegalArgumentException.class,()->new NuStatementParser().parse(text,Instant.parse("2027-08-01T18:00:00Z")));
        assertThrows(IllegalArgumentException.class,()->new NuStatementParser().parse(text+"\nPago mínimo",Instant.parse("2026-10-15T18:00:00Z")));
        assertThrows(IllegalArgumentException.class,()->new NuStatementParser().parse(text.replace("14 septiembre - 15 octubre","14 septiembre - 16 octubre"),Instant.parse("2026-10-15T18:00:00Z")));
    }
}
