package co.finanzas.domain.transaction;

import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.DomainChecks;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

public record TransactionMetadata(Money originalAmount, BigDecimal tax4x1000, String cardLast4,
                                  Integer installments, String originProduct, LocalDate nextCutOn) {
    public static final TransactionMetadata EMPTY = new TransactionMetadata(null,null,null,null,null,null);
    public TransactionMetadata {
        if (tax4x1000 != null) {
            if (tax4x1000.signum() < 0 || tax4x1000.compareTo(Money.MAX_AMOUNT) > 0) throw new IllegalArgumentException("Invalid tax");
            try { tax4x1000 = tax4x1000.setScale(2, RoundingMode.UNNECESSARY); }
            catch (ArithmeticException exception) { throw new IllegalArgumentException("Invalid tax precision"); }
        }
        if (cardLast4 != null && !cardLast4.matches("[0-9]{4}")) throw new IllegalArgumentException("Invalid last four digits");
        if (installments != null && (installments < 1 || installments > 1200)) throw new IllegalArgumentException("Invalid installments");
        originProduct = DomainChecks.optionalText(originProduct,"originProduct",120);
    }
}
