package co.finanzas.adapters.out.parser;

import co.finanzas.domain.shared.Money;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Explicit COP amounts only. Reject signs, prose, ambiguous separators and partial matches. */
public final class ColombianPesoParser {
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
