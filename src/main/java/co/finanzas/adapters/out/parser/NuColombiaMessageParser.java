package co.finanzas.adapters.out.parser;

import co.finanzas.adapters.shared.ingestion.IngestionRequest;
import co.finanzas.application.processing.model.*;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.transaction.*;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static co.finanzas.application.processing.model.ProcessingFailure.Code.*;

/** Whole-message templates based on supplied Nu CO samples. No fuzzy or first-amount matching. */
public final class NuColombiaMessageParser {
    private final ColombianPesoParser amounts = new ColombianPesoParser();
    private final MessageTimestampParser dates = new MessageTimestampParser();

    public ParsedMovement parse(IngestionRequest request) {
        String text = request.message().text();
        if (text.length()>8192) throw new ProcessingFailure(INVALID_PAYLOAD,false);
        String[] lines = Normalizer.normalize(text.replace("\r\n","\n").replace('\r','\n')
                .replace('\u00a0',' ').replace('\u202f',' '),Normalizer.Form.NFC).strip().split("\n",-1);
        for(int i=0;i<lines.length;i++) {
            lines[i]=lines[i].strip();
            if(lines[i].isEmpty() || lines[i].length()>500 || lines[i].chars().anyMatch(Character::isISOControl)) invalid();
        }
        if(request.trigger().equals("EMAIL")) return payment(request,lines);
        if(!request.trigger().equals("APP_DETAIL") || request.destinationAccountId()!=null) unavailable();
        if(lines.length==6 && lines[0].startsWith("Recibiste de ")) return incoming(request,lines);
        if(lines.length==8 && lines[3].equals("Transferencia inmediata | Bre-B")) return outgoing(request,lines);
        if(lines.length==7 || lines.length==8 || lines.length==9) return purchase(request,lines);
        unavailable(); return null;
    }

    private ParsedMovement incoming(IngestionRequest request,String[] l) {
        exact(l[3],"Aceptada | Transferencia Bre-B");
        field(l[4],"Llave: ","[0-9A-Za-z@+._ -]{1,120}");
        field(l[5],"Institución de origen: ","[^\\p{Cntrl}]{1,120}");
        return movement(request,TransactionKind.INCOME,TransactionStatus.POSTED,amounts.parseNu(l[1]),
                dates.parseNuApp(l[2]),field(l[0],"Recibiste de ","[^\\p{Cntrl}]{1,200}"),null,TransactionMetadata.EMPTY,"nu-co-breb-in-v1");
    }

    private ParsedMovement outgoing(IngestionRequest request,String[] l) {
        Money principal=amounts.parseNu(l[1]);
        Money total=amounts.parseNu(field(l[4],"Monto total: ",".+"));
        BigDecimal tax=amounts.parseNuNonNegative(field(l[5],"Impuesto 4x1.000: ",".+"));
        if(total.amount().compareTo(principal.amount())!=0
                && total.amount().compareTo(principal.amount().add(tax))!=0) invalid();
        if(tax.compareTo(total.amount())>0) invalid();
        field(l[6],"Enviaste a su cuenta de: ","[^\\p{Cntrl}]{1,120}");
        field(l[7],"Llave: ","[0-9A-Za-z@+._ -]{1,120}");
        return movement(request,TransactionKind.EXPENSE,TransactionStatus.POSTED,total,dates.parseNuApp(l[2]),
                field(l[0],"","[^\\p{Cntrl}]{1,200}"),null,new TransactionMetadata(null,tax,null,null,null,null),"nu-co-breb-out-v1");
    }

    private ParsedMovement purchase(IngestionRequest request,String[] l) {
        boolean foreign=l.length==9;
        boolean stated=l.length==8 || foreign;
        int dateIndex=foreign ? 3 : 2;
        int merchantIndex=stated ? dateIndex+2 : dateIndex+1;
        TransactionStatus status=TransactionStatus.POSTED;
        TransactionKind kind=TransactionKind.EXPENSE;
        if(stated) {
            String value=field(l[dateIndex+1],"Estado: ","Cancelada|Reembolsada|Reembolso|Aprobada");
            if(value.equals("Cancelada")) status=TransactionStatus.VOIDED;
            else if(value.equals("Reembolsada") || value.equals("Reembolso")) kind=TransactionKind.INCOME;
        }
        String merchant=field(l[merchantIndex],"Establecimiento: ","[^\\p{Cntrl}]{1,200}");
        exact(l[0],merchant);
        field(l[merchantIndex+1],"Categoría: ","[^\\p{Cntrl}]{1,80}");
        String last4=field(l[merchantIndex+2],"Tarjeta virtual: •••• ","[0-9]{4}");
        Money amount=amounts.parseNu(l[1]);
        Money original=null; Integer installments=null;
        if(foreign) {
            original=amounts.parseNuUsd(l[2]);
            Money repeated=amounts.parseNuUsd(field(l[8],"Valor original: ",".+"));
            if(!original.equals(repeated)) invalid();
        } else {
            var quota=Pattern.compile("Cuotas: ([1-9][0-9]{0,3}) (?:cuota|cuotas) de (\\$[^\\n]{1,64})").matcher(l[merchantIndex+3]);
            if(!quota.matches()) invalid();
            installments=Integer.valueOf(quota.group(1));
            if(installments>1200 || amounts.parseNu(quota.group(2)).amount().multiply(BigDecimal.valueOf(installments))
                    .compareTo(amount.amount())!=0) unavailable();
        }
        return movement(request,kind,status,amount,dates.parseNuApp(l[dateIndex]),merchant,null,
                new TransactionMetadata(original,null,last4,installments,null,null),"nu-co-card-v1");
    }

    private ParsedMovement payment(IngestionRequest request,String[] l) {
        if(l.length!=12 || !l[0].equals("Asunto: ¡Recibimos tu pago! Ya puedes estar relax.")) unavailable();
        field(l[1],"Hola, ","[^\\p{Cntrl}]{1,120}:");
        exact(l[2],"Recibimos el pago que hiciste de tu Tarjeta de crédito Nu.");
        exact(l[3],"Estos son los detalles de lo que pagaste:");
        Money amount=amounts.parseNu(field(l[4],"Tu pago fue de: ",".+"));
        Instant occurredAt=dates.parseNuEmail(field(l[5],"Lo has realizado el: ",".+"));
        exact(l[6],"Tipo de transacción: Pago en línea");
        if(amounts.parseNuNonNegative(field(l[7],"Costo de transacción: ",".+")).signum()!=0) unavailable();
        String origin=field(l[8],"Producto origen: ","[A-Za-z0-9_-]{1,120}");
        String destination=field(l[9],"Producto destino: **** **** **** ","[0-9]{4}");
        String bankId=field(l[10],"ID transacción: ","[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        if(request.destinationAccountId()==null || request.destinationAccountId().equals(request.accountId())) {
            throw new ProcessingFailure(ACCOUNT_MAPPING_REQUIRED,false);
        }
        var cut=dates.parseNuCutDate(field(l[11],"Tu próxima fecha de corte es: ",".+"));
        if(cut.isBefore(occurredAt.atZone(java.time.ZoneId.of("America/Bogota")).toLocalDate())) invalid();
        return movement(request,TransactionKind.TRANSFER,TransactionStatus.POSTED,amount,occurredAt,"Pago Tarjeta Nu",
                UUID.fromString(bankId).toString(),new TransactionMetadata(null,null,destination,null,origin,cut),"nu-co-card-payment-v1");
    }

    private static ParsedMovement movement(IngestionRequest request,TransactionKind kind,TransactionStatus status,
            Money amount,Instant at,String merchant,String bankId,TransactionMetadata metadata,String version) {
        return new ParsedMovement(request.accountId(),kind,amount,at,merchant,"NU_CO",bankId,version,
                request.destinationAccountId(),status,metadata);
    }
    private static String field(String line,String prefix,String regex) {
        if(!line.startsWith(prefix)) { invalid(); }
        String value=line.substring(prefix.length());
        if(!value.matches(regex)) invalid();
        return value;
    }
    private static void exact(String actual,String expected) { if(!actual.equals(expected)) invalid(); }
    private static void invalid() { throw new ProcessingFailure(INVALID_PAYLOAD,false); }
    private static void unavailable() { throw new ProcessingFailure(PARSER_UNAVAILABLE,false); }
}
