package co.finanzas.application.email.port.out;

import co.finanzas.application.email.model.*;
import co.finanzas.application.ingestion.model.EncryptedPayload;
import java.util.Optional;
public interface EmailInboxRepository {
    Optional<MailboxLease> claim();
    boolean initialize(MailboxLease lease,long uidValidity,long baselineUid);
    boolean save(MailboxLease lease,long uidValidity,FetchedMail mail,DecodedMail decoded,
                 byte[] hash,EncryptedPayload payload);
    boolean advance(MailboxLease lease,long scannedThrough);
    void release(MailboxLease lease);
    void failed(MailboxLease lease,String code);
}
