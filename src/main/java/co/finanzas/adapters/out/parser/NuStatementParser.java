package co.finanzas.adapters.out.parser;

import co.finanzas.domain.billing.BillingSnapshot;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

public final class NuStatementParser {
    private final MessageTimestampParser dates=new MessageTimestampParser();
    public BillingSnapshot parse(String body,Instant receivedAt) {
        var lines=body.lines().map(String::strip).filter(s->!s.isEmpty()).toList();
        if(lines.isEmpty() || !lines.getFirst().equals("Así viviste un NUevo mes con tu Tarjeta Nu"))invalid();
        String amount=value(lines,"Pago mínimo","El monto más pequeño a pagar para estar al día con tu tarjeta.");
        String period=value(lines,"Periodo facturado","El tiempo donde agrupamos las transacciones que hiciste con tu tarjeta Nu.");
        String cut=value(lines,"Fecha de corte","El día que cierra tu factura con las compras de los últimos 30 días.");
        String due=value(lines,"Fecha límite de pago","El día a pagar tus cuotas sin entrar en mora.");
        var range=Pattern.compile("([0-9]{1,2} [a-záéíóú]+) - ([0-9]{1,2} [a-záéíóú]+)").matcher(period);
        if(!range.matches() || !range.group(2).equals(cut))invalid();
        LocalDate received=receivedAt.atZone(ZoneId.of("America/Bogota")).toLocalDate();
        LocalDate cutDate=dates.parseNuCutDate(cut+" "+received.getYear());
        if(cutDate.isAfter(received))cutDate=dates.parseNuCutDate(cut+" "+(received.getYear()-1));
        if(ChronoUnit.DAYS.between(cutDate,received)>62)invalid();
        LocalDate start=dates.parseNuCutDate(range.group(1)+" "+cutDate.getYear());
        if(!start.isBefore(cutDate))start=dates.parseNuCutDate(range.group(1)+" "+(cutDate.getYear()-1));
        LocalDate end=dates.parseNuCutDate(due+" "+cutDate.getYear());
        if(!end.isAfter(cutDate))end=dates.parseNuCutDate(due+" "+(cutDate.getYear()+1));
        return new BillingSnapshot(new ColombianPesoParser().parseNuNonNegative(amount),start,cutDate,cutDate,end);
    }
    private static String value(List<String> lines,String label,String description) {
        int index=lines.indexOf(label);
        if(index<0 || index!=lines.lastIndexOf(label) || index+2>=lines.size() || !lines.get(index+1).equals(description))invalid();
        return lines.get(index+2);
    }
    private static void invalid(){throw new IllegalArgumentException("Invalid Nu monthly statement");}
}
