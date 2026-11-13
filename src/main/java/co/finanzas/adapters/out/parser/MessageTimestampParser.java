package co.finanzas.adapters.out.parser;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Only complete timestamps; never substitute a message's receipt time for the movement time. */
public final class MessageTimestampParser {
    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    public Instant parseOffset(String value) {
        if (value == null || value.length() > 40) throw new IllegalArgumentException("Invalid movement timestamp");
        return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    }

    /** Call only from a verified Colombia template that establishes the local time zone. */
    public Instant parseBogota(String value) {
        if (value == null || value.length() != 19) throw new IllegalArgumentException("Complete local timestamp required");
        return LocalDateTime.parse(value, LOCAL).atZone(BOGOTA).toInstant();
    }
}
