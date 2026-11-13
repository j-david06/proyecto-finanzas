package co.finanzas.application.email.service;

import co.finanzas.application.email.model.*;
import co.finanzas.application.email.port.in.PollMailboxUseCase;
import co.finanzas.application.email.port.out.*;
import co.finanzas.application.ingestion.port.out.*;
import java.nio.charset.StandardCharsets;

public final class PollMailboxService implements PollMailboxUseCase {
    private final EmailInboxRepository repository;
    private final MailReaderPort reader;
    private final MailDecoderPort decoder;
    private final PayloadCipherPort cipher;
    private final DigestPort digests;
    private final boolean backfill;
    public PollMailboxService(EmailInboxRepository repository,MailReaderPort reader,MailDecoderPort decoder,
                               PayloadCipherPort cipher,DigestPort digests,boolean backfill) {
        this.repository=repository;this.reader=reader;this.decoder=decoder;this.cipher=cipher;this.digests=digests;this.backfill=backfill;
    }
    @Override public int poll() {
        var claimed=repository.claim();
        if(claimed.isEmpty())return 0;
        var lease=claimed.orElseThrow();
        try {
            var batch=reader.read(lease);
            if(lease.uidValidity()==null) {
                if(!repository.initialize(lease,batch.uidValidity(),backfill?0:batch.nextUid()-1))return 0;
                return 0; // next poll reads using the persisted UID namespace.
            }
            if(lease.uidValidity()!=batch.uidValidity())throw new IllegalStateException("UIDVALIDITY_CHANGED");
            long previous=lease.lastUid();int accepted=0;
            for(var mail:batch.messages()) {
                if(mail.uid()<=previous || mail.uid()>batch.scannedThrough())throw new IllegalStateException("INVALID_UID_ORDER");
                var decoded=mail.rejectionCode()==null?decoder.decode(lease,mail):DecodedMail.rejected(
                        mail.rejectionCode().equals("SENDER_NOT_ALLOWED")?"IGNORED":"REVIEW",mail.rejectionCode());
                byte[] payload=decoded.payload();
                byte[] aad=(lease.ownerId().value()+":"+lease.connectionId()+":imap:"+batch.uidValidity()+":"+mail.uid()).getBytes(StandardCharsets.UTF_8);
                try {
                    if(!repository.save(lease,batch.uidValidity(),mail,decoded,payload==null?null:digests.sha256(payload),
                            payload==null?null:cipher.encrypt(payload,aad)))return accepted;
                } finally { if(payload!=null)java.util.Arrays.fill(payload,(byte)0); }
                previous=mail.uid();accepted++;
            }
            repository.advance(lease,batch.scannedThrough());
            return accepted;
        } catch(RuntimeException exception) {
            repository.failed(lease,"UIDVALIDITY_CHANGED".equals(exception.getMessage())?"UIDVALIDITY_CHANGED":"MAILBOX_POLL_FAILED");
            throw exception;
        } finally { repository.release(lease); }
    }
}
