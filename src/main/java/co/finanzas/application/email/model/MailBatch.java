package co.finanzas.application.email.model;
import java.util.List;
public record MailBatch(long uidValidity,long nextUid,long scannedThrough,List<FetchedMail> messages) {
    public MailBatch {
        messages=List.copyOf(messages);
        if(uidValidity<1 || uidValidity>4294967295L || nextUid<1 || nextUid>4294967296L
                || scannedThrough<0 || scannedThrough>=nextUid || messages.size()>10)throw new IllegalArgumentException("Invalid bounded IMAP batch");
    }
}
