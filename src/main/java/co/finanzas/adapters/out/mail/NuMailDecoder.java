package co.finanzas.adapters.out.mail;

import co.finanzas.application.email.model.*;
import co.finanzas.application.email.port.out.MailDecoderPort;
import co.finanzas.adapters.out.parser.*;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.util.*;

public final class NuMailDecoder implements MailDecoderPort {
    public static final String PAYMENT="¡Ya entró tu pago!",STATEMENT="Llegó el extracto de tu tarjeta de crédito Nu";
    private final GmailAuthenticationPolicy authentication;
    private final ObjectMapper mapper;
    private final IngestionMovementParser parser;
    public NuMailDecoder(GmailAuthenticationPolicy authentication,ObjectMapper mapper,IngestionRequestDecoder decoder){
        this.authentication=authentication;this.mapper=mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.parser=new IngestionMovementParser(decoder);
    }
    @Override public DecodedMail decode(MailboxLease lease,FetchedMail mail) {
        try {
            if(mail.bytes()==null || mail.bytes().length>524288)return DecodedMail.rejected("REVIEW","EMAIL_TOO_LARGE");
            var message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(mail.bytes()));
            String rejection=authentication.rejection(message);
            if(rejection!=null)return DecodedMail.rejected(rejection.equals("SENDER_NOT_ALLOWED")?"IGNORED":"REVIEW",rejection);
            String subject=message.getSubject();
            if(!PAYMENT.equals(subject) && !STATEMENT.equals(subject))return DecodedMail.rejected("REVIEW","UNSUPPORTED_NU_SUBJECT");
            String body=new MimeTextExtractor().extract(message);
            if(STATEMENT.equals(subject)) {
                var snapshot=new NuStatementParser().parse(body,mail.receivedAt());
                byte[] evidence=mapper.writeValueAsBytes(Map.of("subject",subject,"body",body));
                return new DecodedMail("STATEMENT","ALERT",null,evidence,snapshot.cutOffDate(),snapshot);
            }
            byte[] payload=mapper.writeValueAsBytes(Map.of("schema_version",1,"trigger","EMAIL","format","RAW_MESSAGE",
                    "account_id",lease.accountId(),"destination_account_id",lease.destinationAccountId(),
                    "message",Map.of("text","Asunto: "+subject+"\n"+body,"received_at",mail.receivedAt().atOffset(java.time.ZoneOffset.UTC).toString())));
            parser.parse(payload); // Only a verified payment template may enter the financial queue.
            return new DecodedMail("CARD_PAYMENT","QUEUED",null,payload,null);
        } catch(Exception exception) {
            return DecodedMail.rejected("REVIEW","INVALID_EMAIL_CONTENT");
        }
    }
}
