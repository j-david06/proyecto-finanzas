package co.finanzas.ingestion;

import co.finanzas.adapters.out.parser.IngestionMovementParser;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.application.processing.model.*;
import co.finanzas.domain.transaction.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class NuMessageParserTest {
    public static String sample(String name) {
        try(var input=NuMessageParserTest.class.getResourceAsStream("/nu/"+name+".txt")) {
            return new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
    public static String body(UUID account,UUID destination,String trigger,String text) {
        try {
            var payload=new LinkedHashMap<String,Object>();
            payload.put("schema_version",1);payload.put("trigger",trigger);payload.put("format","RAW_MESSAGE");
            payload.put("account_id",account.toString());
            if(destination!=null) payload.put("destination_account_id",destination.toString());
            payload.put("message",Map.of("text",text,"received_at","2026-11-14T12:00:00-05:00"));
            return new ObjectMapper().writeValueAsString(payload);
        } catch(Exception exception) { throw new IllegalArgumentException(exception); }
    }
    private ParsedMovement parse(String name,String text) {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var parser=new IngestionMovementParser(new IngestionRequestDecoder(new ObjectMapper(),factory.getValidator()));
            return parser.parse(body(UUID.randomUUID(),name.equals("card-payment")?UUID.randomUUID():null,
                    name.equals("card-payment")?"EMAIL":"APP_DETAIL",text).getBytes(StandardCharsets.UTF_8));
        }
    }
    @Test void extractsIncomingBreBWithMovementTimeAndNoInventedReference() {
        var movement=parse("breb-in",sample("breb-in"));
        assertEquals(TransactionKind.INCOME,movement.kind()); assertEquals(new BigDecimal("20000.00"),movement.amount().amount());
        assertEquals(Instant.parse("2026-11-13T17:36:00Z"),movement.occurredAt()); assertNull(movement.bankEventId());
        assertEquals("Persona Ejemplo",movement.merchant());
    }
    @Test void extractsOutgoingBreBAndZeroOrIncludedTax() {
        var movement=parse("breb-out",sample("breb-out"));
        assertEquals(TransactionKind.EXPENSE,movement.kind()); assertEquals(new BigDecimal("0.00"),movement.metadata().tax4x1000());
        var taxed=parse("breb-out",sample("breb-out").replace("Monto total: $14.000,00","Monto total: $14.056,00")
                .replace("Impuesto 4x1.000: $0,00","Impuesto 4x1.000: $56,00"));
        assertEquals(new BigDecimal("14056.00"),taxed.amount().amount());
    }
    @Test void extractsCardPurchaseWithoutCountingQuotaAsAnotherMovement() {
        var movement=parse("purchase",sample("purchase"));
        assertEquals(new BigDecimal("27900.00"),movement.amount().amount());assertEquals(1,movement.metadata().installments());
        assertEquals("1234",movement.metadata().cardLast4()); assertEquals(TransactionStatus.POSTED,movement.status());
        assertEquals(Instant.parse("2026-11-08T21:28:00Z"),movement.occurredAt());
    }
    @Test void cancelledForeignPurchaseIsVoidedInCopAndRetainsOriginalUsd() {
        var movement=parse("cancelled",sample("cancelled"));
        assertEquals(TransactionStatus.VOIDED,movement.status()); assertEquals(new BigDecimal("3551.09"),movement.amount().amount());
        assertEquals(new BigDecimal("1.10"),movement.metadata().originalAmount().amount());
        assertEquals("USD",movement.metadata().originalAmount().currency().getCurrencyCode());
    }
    @Test void explicitlyRefundedCardDetailIsAnIncomeWithoutRewritingTheOriginal() {
        var movement=parse("cancelled",sample("cancelled").replace("Estado: Cancelada","Estado: Reembolsada"));
        assertEquals(TransactionKind.INCOME,movement.kind()); assertEquals(TransactionStatus.POSTED,movement.status());
    }
    @Test void paymentIsSingleTransferWithStableBankIdentityAndCutDate() {
        var movement=parse("card-payment",sample("card-payment"));
        assertEquals(TransactionKind.TRANSFER,movement.kind());assertNotNull(movement.destinationAccountId());
        assertEquals("00000000-0000-0000-0000-000000000001",movement.bankEventId());
        assertEquals(Instant.parse("2026-11-13T03:31:38Z"),movement.occurredAt());
        assertEquals("2026-11-14",movement.metadata().nextCutOn().toString());
        assertEquals("ABC123",movement.metadata().originProduct());assertEquals("9876",movement.metadata().cardLast4());
    }
    @Test void rejectsOcrAmbiguityWrongGroupingContradictionsExtraOrRepeatedFields() {
        for(String invalid:List.of(sample("purchase").replace("27.900,00","27,900.00"),
                sample("purchase").replace("Domingo","Lunes"),sample("purchase").replace("8 de Noviembre","31 de Noviembre"),
                sample("purchase").replace("Establecimiento: Rappi","Establecimiento: Otro"),
                sample("purchase")+"\nMonto: $10,00",sample("purchase")+sample("purchase"),
                sample("purchase").replace("1 cuota de $27.900,00","2 cuotas de $13.000,00"))) {
            assertThrows(ProcessingFailure.class,()->parse("purchase",invalid));
        }
        assertThrows(ProcessingFailure.class,()->parse("cancelled",sample("cancelled").replace("Valor original: US$ 1,10","Valor original: US$ 2,10")));
        assertThrows(ProcessingFailure.class,()->parse("card-payment",sample("card-payment").replace("Costo de transacción: $0","Costo de transacción: $500")));
    }
    @Test void installmentCountRequiresExactConsistencyWithTheDisplayedTotal() {
        var movement=parse("purchase",sample("purchase").replace("1 cuota de $27.900,00","2 cuotas de $13.950,00"));
        assertEquals(2,movement.metadata().installments());assertEquals(new BigDecimal("27900.00"),movement.amount().amount());
    }
}
