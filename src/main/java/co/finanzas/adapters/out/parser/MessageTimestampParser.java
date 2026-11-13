package co.finanzas.adapters.out.parser;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

/** Only complete timestamps; never substitute a message's receipt time for the movement time. */
public final class MessageTimestampParser {
    private static final java.util.Map<String,Integer> MONTHS = java.util.Map.ofEntries(
        java.util.Map.entry("enero",1),java.util.Map.entry("febrero",2),java.util.Map.entry("marzo",3),
        java.util.Map.entry("abril",4),java.util.Map.entry("mayo",5),java.util.Map.entry("junio",6),
        java.util.Map.entry("julio",7),java.util.Map.entry("agosto",8),java.util.Map.entry("septiembre",9),
        java.util.Map.entry("octubre",10),java.util.Map.entry("noviembre",11),java.util.Map.entry("diciembre",12));
    private static final java.util.List<String> WEEKDAYS = java.util.List.of("lunes","martes","miércoles","jueves","viernes","sábado","domingo");

    public Instant parseNuApp(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        var shortDate = java.util.regex.Pattern.compile("([0-9]{1,2}) ([a-z]{3}) ([0-9]{4}), ([0-9]{2}):([0-9]{2})").matcher(lower);
        if (shortDate.matches()) {
            int month = MONTHS.entrySet().stream().filter(e -> e.getKey().startsWith(shortDate.group(2)))
                    .map(java.util.Map.Entry::getValue).findFirst().orElseThrow(IllegalArgumentException::new);
            return local(shortDate.group(3),month,shortDate.group(1),shortDate.group(4),shortDate.group(5),0).atZone(BOGOTA).toInstant();
        }
        var longDate = java.util.regex.Pattern.compile("([0-9]{1,2}) de ([a-z]+) de ([0-9]{4}), ([0-9]{2}):([0-9]{2})").matcher(lower);
        if (longDate.matches()) return local(longDate.group(3),month(longDate.group(2)),longDate.group(1),longDate.group(4),longDate.group(5),0).atZone(BOGOTA).toInstant();
        var card = java.util.regex.Pattern.compile("([0-9]{2}):([0-9]{2}), ([a-záéíóú]+), ([0-9]{1,2}) de ([a-z]+) de ([0-9]{4})").matcher(lower);
        if (!card.matches()) throw new IllegalArgumentException("Unsupported Nu app date");
        var date = local(card.group(6),month(card.group(5)),card.group(4),card.group(1),card.group(2),0);
        if (!WEEKDAYS.get(date.getDayOfWeek().getValue()-1).equals(card.group(3))) throw new IllegalArgumentException("Weekday contradicts date");
        return date.atZone(BOGOTA).toInstant();
    }

    public Instant parseNuEmail(String value) {
        var match = java.util.regex.Pattern.compile("([0-9]{1,2}) ([a-zA-Z]+) ([0-9]{4}) ([0-9]{1,2}):([0-9]{2}):([0-9]{2}) (AM|PM)").matcher(value);
        if (!match.matches()) throw new IllegalArgumentException("Unsupported Nu email date");
        int hour = Integer.parseInt(match.group(4));
        if (hour < 1 || hour > 12) throw new IllegalArgumentException("Invalid twelve-hour time");
        hour = hour % 12 + (match.group(7).equals("PM") ? 12 : 0);
        return local(match.group(3),month(match.group(2).toLowerCase(java.util.Locale.ROOT)),match.group(1),Integer.toString(hour),match.group(5),Integer.parseInt(match.group(6))).atZone(BOGOTA).toInstant();
    }

    public LocalDate parseNuCutDate(String value) {
        var match = java.util.regex.Pattern.compile("([0-9]{1,2}) ([a-zA-Z]+) ([0-9]{4})").matcher(value);
        if (!match.matches()) throw new IllegalArgumentException("Unsupported cut date");
        return LocalDate.of(Integer.parseInt(match.group(3)),month(match.group(2).toLowerCase(java.util.Locale.ROOT)),Integer.parseInt(match.group(1)));
    }

    private static int month(String value) {
        Integer result = MONTHS.get(value);
        if (result == null) throw new IllegalArgumentException("Invalid month");
        return result;
    }
    private static LocalDateTime local(String year,int month,String day,String hour,String minute,int second) {
        if(Integer.parseInt(year)<1)throw new IllegalArgumentException("Invalid year");
        return LocalDateTime.of(Integer.parseInt(year),month,Integer.parseInt(day),Integer.parseInt(hour),Integer.parseInt(minute),second);
    }
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
