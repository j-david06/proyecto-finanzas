package co.finanzas.ingestion;

import co.finanzas.adapters.out.mail.*;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.application.email.model.*;
import co.finanzas.domain.shared.OwnerId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class MailDecoderTest {
    public static final String SENDER="nu@example.test",AUTH="mx.google.com; dkim=pass header.i=@example.test; dmarc=pass (p=REJECT) header.from=example.test";
    public static MimeMessage message(String subject,String body,String auth) throws Exception {
        var m=new MimeMessage(Session.getInstance(new Properties()));m.setFrom(new InternetAddress(SENDER));m.setSubject(subject,"UTF-8");
        m.setText(body,"UTF-8");if(auth!=null)m.setHeader("Authentication-Results",auth);m.saveChanges();return m;
    }
    public static byte[] bytes(MimeMessage m) throws Exception {var out=new ByteArrayOutputStream();m.writeTo(out);return out.toByteArray();}
    public static String payment(){return NuMessageParserTest.sample("card-payment").replace("Asunto: ¡Recibimos tu pago! Ya puedes estar relax.","¡Recibimos tu pago! Ya puedes estar relax.");}
    public static DecodedMail decode(MailboxLease lease,MimeMessage message,Instant received) throws Exception {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var mapper=new ObjectMapper();
            return new NuMailDecoder(new GmailAuthenticationPolicy(SENDER),mapper,new IngestionRequestDecoder(mapper,factory.getValidator()))
                    .decode(lease,new FetchedMail(1,received,bytes(message),null));
        }
    }
    public static MailboxLease lease(){return new MailboxLease(UUID.randomUUID(),new OwnerId(UUID.randomUUID()),UUID.randomUUID(),UUID.randomUUID(),"Nu",1L,0,UUID.randomUUID());}
    @Test void authenticPaymentUsesRealGmailSubjectAndQueuesCanonicalPayload() throws Exception {
        var r=decode(lease(),message(NuMailDecoder.PAYMENT,payment(),AUTH),Instant.parse("2026-11-13T18:00:00Z"));
        assertEquals("QUEUED",r.state());assertEquals("CARD_PAYMENT",r.kind());assertNotNull(r.payload());
        assertTrue(new String(r.payload(),java.nio.charset.StandardCharsets.UTF_8).contains("¡Ya entró tu pago!"));
    }
    @Test void statementIsNonFinancialAndUsesTrustedReceiptTimestamp() throws Exception {
        var r=decode(lease(),message(NuMailDecoder.STATEMENT,NuMessageParserTest.sample("statement"),AUTH),Instant.parse("2026-10-15T18:00:00Z"));
        assertEquals("ALERT",r.state());assertNotNull(r.billing());assertEquals("2026-11-04",r.billing().paymentDueDate().toString());
    }
    @Test void spoofedFromMissingDmarcAndUntrustedAuthenticationNeverQueue() throws Exception {
        for(String auth:List.of("mx.google.com; dmarc=fail header.from=example.test","attacker.test; dmarc=pass header.from=example.test",
                "mx.google.com; dmarc=pass header.from=evil.test","mx.google.com; dmarc=pass header.from=evil.test header.from=example.test",
                "mx.google.com; spf=pass (dmarc=pass header.from=example.test)")) {
            assertEquals("REVIEW",decode(lease(),message(NuMailDecoder.PAYMENT,payment(),auth),Instant.now()).state());
        }
        assertEquals("REVIEW",decode(lease(),message(NuMailDecoder.PAYMENT,payment(),null),Instant.now()).state());
        var m=message(NuMailDecoder.PAYMENT,payment(),AUTH);m.setFrom(new InternetAddress("other@example.test"));
        assertEquals("IGNORED",decode(lease(),m,Instant.now()).state());
    }
    @Test void duplicateTrustedAuthenticationIsRejected() throws Exception {
        var m=message(NuMailDecoder.PAYMENT,payment(),AUTH);m.addHeader("Authentication-Results",AUTH);
        assertEquals("REVIEW",decode(lease(),m,Instant.now()).state());
    }
    @Test void malformedAuthenticationIsAReviewCodeRatherThanAPollBlockingException()throws Exception{
        var policy=new GmailAuthenticationPolicy(SENDER);
        for(String header:List.of("mx.google.com; dmarc=\"pass\" header.from=example.test","mx.google.com; dmarc=pass (unclosed")) {
            assertEquals("INVALID_AUTHENTICATION_HEADER",policy.rejection(message(NuMailDecoder.PAYMENT,payment(),header)));
        }
    }
    @Test void htmlBodyIsExtractedWithoutOpeningPdfAttachmentOrLoadingResources() throws Exception {
        var message=message(NuMailDecoder.STATEMENT,"",AUTH);var mixed=new MimeMultipart("mixed");
        var html=new MimeBodyPart();String body=NuMessageParserTest.sample("statement").lines().map(s->"<p>"+s+"</p>").collect(java.util.stream.Collectors.joining());
        html.setContent("<html><head><script>throw 1</script></head><body>"+body+"<img src='https://invalid.example/tracker'></body></html>","text/html; charset=UTF-8");mixed.addBodyPart(html);
        var pdf=new MimeBodyPart();pdf.setContent("not a PDF","application/pdf");pdf.setFileName("Nu_2026-10-15.pdf");pdf.setDisposition(Part.ATTACHMENT);mixed.addBodyPart(pdf);
        message.setContent(mixed);message.saveChanges();
        assertEquals("ALERT",decode(lease(),message,Instant.parse("2026-10-15T18:00:00Z")).state());
    }
    @Test void imapRequiresCertificateValidationAndPeek() {
        var p=GmailImapReader.secureProperties(false);assertEquals("true",p.getProperty("mail.imaps.ssl.checkserveridentity"));
        assertEquals("true",p.getProperty("mail.imaps.peek"));assertNull(p.getProperty("mail.imaps.ssl.trust"));
    }
}
