package co.finanzas.adapters.out.parser;

import co.finanzas.domain.shared.Money;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Explicit COP amounts only. Reject signs, prose, ambiguous separators and partial matches. */
public final class ColombianPesoParser {
    private static final Pattern NU_NUMBER = Pattern.compile("(?:0|[1-9][0-9]*|[1-9][0-9]{0,2}(?:\\.[0-9]{3})+)(?:,[0-9]{2})?");

    /** The verified Nu Colombia template establishes COP even when its label is only '$'. */
    public Money parseNu(String value) { return new Money(parseNuNonNegative(value)); }

    public BigDecimal parseNuNonNegative(String value) {
        if (value == null || value.length() > 64 || !value.startsWith("$")) throw new IllegalArgumentException("Invalid Nu COP amount");
        String number = value.substring(1).strip();
        if (!NU_NUMBER.matcher(number).matches()) throw new IllegalArgumentException("Invalid Nu Colombian separators");
        BigDecimal amount = new BigDecimal(number.replace(".", "").replace(',', '.')).setScale(2);
        if (amount.compareTo(Money.MAX_AMOUNT) > 0) throw new IllegalArgumentException("Amount exceeds storage limits");
        return amount;
    }

    public Money parseNuUsd(String value) {
        if (value == null || !value.startsWith("US$ ")) throw new IllegalArgumentException("Invalid USD label");
        return new Money(parseNuNonNegative("$" + value.substring(4)), java.util.Currency.getInstance("USD"));
    }
    private static final Pattern LOCAL_GROUPED = Pattern.compile("[1-9][0-9]{0,2}(?:\\.[0-9]{3})+(?:,[0-9]{2})?");
    private static final Pattern INTERNATIONAL_GROUPED = Pattern.compile("[1-9][0-9]{0,2}(?:,[0-9]{3})+(?:\\.[0-9]{2})?");
    private static final Pattern UNGROUPED = Pattern.compile("(?:0|[1-9][0-9]*)(?:[.,][0-9]{2})?");

    public Money parse(String value) {
        if (value == null || value.length() > 64) throw new IllegalArgumentException("Invalid COP amount");
        String text = value.replace('\u00a0', ' ').replace('\u202f', ' ').strip();
        // A bare '$' is insufficient to establish a country/currency in a bank message.
        if (text.startsWith("COP ")) text = text.substring(4).strip();
        else if (text.endsWith(" COP")) text = text.substring(0, text.length() - 4).strip();
        else throw new IllegalArgumentException("Explicit COP currency required");
        if (text.startsWith("$")) text = text.substring(1).strip();
        String decimal;
        if (LOCAL_GROUPED.matcher(text).matches()) decimal = text.replace(".", "").replace(',', '.');
        else if (INTERNATIONAL_GROUPED.matcher(text).matches()) decimal = text.replace(",", "");
        else if (UNGROUPED.matcher(text).matches()) decimal = text.replace(',', '.');
        else throw new IllegalArgumentException("Invalid COP amount format");
        return new Money(new BigDecimal(decimal));
    }
}
