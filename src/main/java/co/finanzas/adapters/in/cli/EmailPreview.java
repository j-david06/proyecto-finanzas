package co.finanzas.adapters.in.cli;

import co.finanzas.adapters.out.mail.*;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.application.email.model.*;
import co.finanzas.domain.shared.OwnerId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Offline preview only. A file's Authentication-Results is not proof of Gmail delivery. No DB writes. */
public final class EmailPreview {
    public String inspect(Path file,String sender,Instant receivedAt) throws Exception {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>524288)throw new IllegalArgumentException("Invalid preview file");
        var mapper=new ObjectMapper();
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var decoder=new NuMailDecoder(new GmailAuthenticationPolicy(sender),mapper,new IngestionRequestDecoder(mapper,factory.getValidator()));
            var lease=new MailboxLease(UUID.randomUUID(),new OwnerId(UUID.randomUUID()),UUID.randomUUID(),UUID.randomUUID(),"PREVIEW",1L,0,UUID.randomUUID());
            var result=decoder.decode(lease,new FetchedMail(1,receivedAt,Files.readAllBytes(file),null));
            var output=new LinkedHashMap<String,Object>();output.put("mode","OFFLINE_PREVIEW_NO_WRITES");
            output.put("kind",result.kind());output.put("state",result.state());output.put("error_code",result.errorCode());
            if(result.billing()!=null) {
                var b=result.billing();
                output.put("minimum_payment",b.minimumPayment().toPlainString());output.put("currency","COP");
                output.put("billing_period_start",b.periodStart().toString());output.put("billing_period_end",b.periodEnd().toString());
                output.put("cut_off_date",b.cutOffDate().toString());output.put("payment_due_date",b.paymentDueDate().toString());
            }
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        }
    }
}
