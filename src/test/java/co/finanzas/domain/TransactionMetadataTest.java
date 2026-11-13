package co.finanzas.domain;

import co.finanzas.domain.shared.Money;
import co.finanzas.domain.transaction.TransactionMetadata;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class TransactionMetadataTest {
    @Test void acceptsZeroTaxAndRejectsInvalidMetadata() {
        assertEquals(new BigDecimal("0.00"),new TransactionMetadata(null,BigDecimal.ZERO,"1234",1,null,null).tax4x1000());
        assertThrows(IllegalArgumentException.class,()->new TransactionMetadata(null,new BigDecimal("0.001"),null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new TransactionMetadata(null,new BigDecimal("-1"),null,null,null,null));
        assertThrows(IllegalArgumentException.class,()->new TransactionMetadata(null,null,"12345",null,null,null));
        assertThrows(IllegalArgumentException.class,()->new TransactionMetadata(null,null,null,0,null,null));
        assertThrows(IllegalArgumentException.class,()->new TransactionMetadata(null,null,null,1201,null,null));
    }
    @Test void voidedObservationsCanBeImportedButAreNotOrdinaryRegistrations() {
        var cancelled=DomainFixtures.movement(DomainFixtures.ACCOUNT,null,null,null,
                co.finanzas.domain.transaction.TransactionKind.EXPENSE,DomainFixtures.AMOUNT,null,null).voidPending();
        assertDoesNotThrow(cancelled::requireIngestible);
        assertThrows(IllegalStateException.class,cancelled::requireRegistrable);
    }
}
