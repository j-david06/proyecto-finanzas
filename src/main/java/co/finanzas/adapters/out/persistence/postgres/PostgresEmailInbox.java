package co.finanzas.adapters.out.persistence.postgres;

import co.finanzas.application.email.model.*;
import co.finanzas.application.email.port.out.EmailInboxRepository;
import co.finanzas.application.ingestion.model.EncryptedPayload;
import co.finanzas.domain.shared.OwnerId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.util.*;

public final class PostgresEmailInbox implements EmailInboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UUID connectionId;
    public PostgresEmailInbox(JdbcTemplate jdbc,TransactionTemplate tx,UUID connectionId){this.jdbc=jdbc;this.tx=tx;this.connectionId=connectionId;}

    public void configure(UUID account,UUID destination,String folder,byte[] loginHash) {
        tx.executeWithoutResult(status->{
            var connection=jdbc.query("SELECT * FROM source_connections WHERE id=? FOR SHARE",(rs,row)->PostgresSourceConnections.map(rs),connectionId)
                    .stream().findFirst().orElseThrow(()->new IllegalStateException("Provision the IMAP source connection first"));
            if(!connection.source().equals("IMAP") || !connection.active() || !connection.allowedAccounts().containsAll(Set.of(account,destination)))
                throw new IllegalStateException("Invalid IMAP account scope");
            Integer count=jdbc.queryForObject("""
                    SELECT count(*) FROM accounts WHERE owner_id=? AND active AND currency='COP'
                      AND ((id=? AND kind='DEPOSIT' AND bank_reference IS NOT NULL)
                        OR (id=? AND kind='CREDIT_CARD' AND card_last4 IS NOT NULL))
                    """,Integer.class,connection.ownerId().value(),account,destination);
            if(count==null || count!=2)throw new IllegalStateException("Configure explicit deposit and credit card mappings first");
            jdbc.update("""
                    INSERT INTO email_mailboxes(source_connection_id,owner_id,account_id,destination_account_id,folder,login_sha256)
                    VALUES(?,?,?,?,?,?) ON CONFLICT(source_connection_id) DO NOTHING
                    """,connectionId,connection.ownerId().value(),account,destination,folder,loginHash);
            Integer matches=jdbc.queryForObject("""
                    SELECT count(*) FROM email_mailboxes WHERE source_connection_id=? AND owner_id=?
                      AND account_id=? AND destination_account_id=? AND folder=? AND login_sha256=?
                    """,Integer.class,connectionId,connection.ownerId().value(),account,destination,folder,loginHash);
            if(matches==null || matches!=1)throw new IllegalStateException("Mailbox binding changed; use a new source connection");
        });
    }
    @Override public Optional<MailboxLease> claim(){
        return tx.execute(status->jdbc.query("""
                WITH ready AS (
                  SELECT m.source_connection_id FROM email_mailboxes m JOIN source_connections c ON c.id=m.source_connection_id
                  WHERE m.source_connection_id=? AND c.active AND c.source='IMAP' AND c.owner_id=m.owner_id
                    AND c.allowed_account_ids @> ARRAY[m.account_id,m.destination_account_id]
                    AND (c.expires_at IS NULL OR c.expires_at>now()) AND (m.lease_until IS NULL OR m.lease_until<=now())
                  FOR UPDATE OF m SKIP LOCKED
                ) UPDATE email_mailboxes m SET lease_token=?,lease_until=now()+interval '180 seconds',updated_at=now()
                  FROM ready r WHERE m.source_connection_id=r.source_connection_id RETURNING m.*
                """,(rs,row)->new MailboxLease(connectionId,new OwnerId(rs.getObject("owner_id",UUID.class)),
                        rs.getObject("account_id",UUID.class),rs.getObject("destination_account_id",UUID.class),rs.getString("folder"),
                        rs.getObject("uid_validity",Long.class),rs.getLong("last_uid"),rs.getObject("lease_token",UUID.class)),
                connectionId,UUID.randomUUID()).stream().findFirst());
    }
    @Override public boolean initialize(MailboxLease lease,long validity,long baseline){
        if(validity<1 || validity>4294967295L || baseline<0 || baseline>4294967295L)throw new IllegalArgumentException("Invalid initial cursor");
        return jdbc.update("""
                UPDATE email_mailboxes m SET uid_validity=?,last_uid=?,error_code=NULL,updated_at=now()
                WHERE source_connection_id=? AND uid_validity IS NULL AND lease_token=? AND lease_until>clock_timestamp()
                  AND EXISTS(SELECT 1 FROM source_connections c WHERE c.id=m.source_connection_id AND c.owner_id=m.owner_id
                    AND c.active AND (c.expires_at IS NULL OR c.expires_at>clock_timestamp())
                    AND c.allowed_account_ids @> ARRAY[m.account_id,m.destination_account_id])
                """,validity,baseline,connectionId,lease.token())==1;
    }
    @Override public boolean save(MailboxLease lease,long validity,FetchedMail mail,DecodedMail decoded,byte[] hash,EncryptedPayload payload){
        return Boolean.TRUE.equals(tx.execute(status->{
            if(!locked(lease,validity))return false;
            var source=jdbc.query("SELECT * FROM source_connections WHERE id=? FOR SHARE",(rs,row)->PostgresSourceConnections.map(rs),connectionId).getFirst();
            if(!source.active() || !source.source().equals("IMAP") || !source.ownerId().equals(lease.ownerId())
                    || !source.allowedAccounts().containsAll(Set.of(lease.accountId(),lease.destinationAccountId()))
                    || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT expires_at IS NULL OR expires_at>clock_timestamp() FROM source_connections WHERE id=?",Boolean.class,connectionId)))
                throw new IllegalStateException("IMAP scope revoked");
            UUID receipt=UUID.randomUUID(),ingestion=decoded.state().equals("QUEUED")?UUID.randomUUID():null;
            String event="imap:"+validity+":"+mail.uid();
            if(ingestion!=null) {
                if(payload==null || hash==null)throw new IllegalArgumentException("Payment evidence required");
                var ids=jdbc.query("""
                        INSERT INTO ingestion_logs(id,owner_id,source,source_connection_id,idempotency_key,payload_sha256,payload_encrypted,payload_key_id)
                        VALUES(?,?,'IMAP',?,?,?,?,?) ON CONFLICT(owner_id,source,source_connection_id,idempotency_key) DO NOTHING RETURNING id
                        """,(rs,row)->rs.getObject(1,UUID.class),ingestion,lease.ownerId().value(),connectionId,event,hash,payload.bytes(),payload.keyId());
                if(ids.isEmpty()) {
                    ingestion=jdbc.queryForObject("SELECT id FROM ingestion_logs WHERE owner_id=? AND source='IMAP' AND source_connection_id=? AND idempotency_key=? AND payload_sha256=?",
                            UUID.class,lease.ownerId().value(),connectionId,event,hash);
                }
            }
            var receipts=jdbc.query("""
                    INSERT INTO email_receipts(id,source_connection_id,uid_validity,message_uid,kind,state,error_code,
                      payload_sha256,payload_encrypted,payload_key_id,cut_on,ingestion_id,received_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source_connection_id,uid_validity,message_uid) DO NOTHING RETURNING id
                    """,(rs,row)->rs.getObject(1,UUID.class),receipt,connectionId,validity,mail.uid(),decoded.kind(),decoded.state(),decoded.errorCode(),
                    hash,payload==null?null:payload.bytes(),payload==null?null:payload.keyId(),decoded.cutOn(),ingestion,Timestamp.from(mail.receivedAt()));
            if(!receipts.isEmpty() && decoded.billing()!=null) {
                var valid=jdbc.query("SELECT id FROM accounts WHERE id=? AND owner_id=? AND kind='CREDIT_CARD' AND currency='COP' AND active FOR SHARE",
                        (rs,row)->rs.getObject(1,UUID.class),lease.destinationAccountId(),lease.ownerId().value());
                if(valid.size()!=1)throw new IllegalStateException("Invalid statement account");
                var bill=decoded.billing();
                jdbc.update("""
                        INSERT INTO billing_statements(id,owner_id,account_id,minimum_payment,billing_period_start,billing_period_end,
                          cut_off_date,payment_due_date,email_receipt_id,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(owner_id,account_id,cut_off_date) DO UPDATE SET minimum_payment=excluded.minimum_payment,
                          billing_period_start=excluded.billing_period_start,billing_period_end=excluded.billing_period_end,
                          payment_due_date=excluded.payment_due_date,email_receipt_id=excluded.email_receipt_id,observed_at=excluded.observed_at
                        WHERE billing_statements.observed_at<excluded.observed_at
                        """,receipt,lease.ownerId().value(),lease.destinationAccountId(),bill.minimumPayment(),bill.periodStart(),bill.periodEnd(),
                        bill.cutOffDate(),bill.paymentDueDate(),receipt,Timestamp.from(mail.receivedAt()));
            }
            int updated=jdbc.update("""
                    UPDATE email_mailboxes SET last_uid=greatest(last_uid,?),error_code=NULL,updated_at=now()
                    WHERE source_connection_id=? AND uid_validity=? AND lease_token=? AND lease_until>clock_timestamp()
                    """,mail.uid(),connectionId,validity,lease.token());
            if(updated!=1){status.setRollbackOnly();return false;}
            return true;
        }));
    }
    private boolean locked(MailboxLease lease,long validity){
        return !jdbc.query("""
                SELECT source_connection_id FROM email_mailboxes WHERE source_connection_id=? AND owner_id=? AND uid_validity=?
                  AND lease_token=? AND lease_until>clock_timestamp() FOR UPDATE
                """,(rs,row)->rs.getObject(1,UUID.class),connectionId,lease.ownerId().value(),validity,lease.token()).isEmpty();
    }
    @Override public boolean advance(MailboxLease lease,long through){
        return jdbc.update("""
                UPDATE email_mailboxes m SET last_uid=greatest(last_uid,?),error_code=NULL,updated_at=now()
                WHERE source_connection_id=? AND uid_validity=? AND lease_token=? AND lease_until>clock_timestamp()
                  AND EXISTS(SELECT 1 FROM source_connections c WHERE c.id=m.source_connection_id AND c.owner_id=m.owner_id
                    AND c.active AND (c.expires_at IS NULL OR c.expires_at>clock_timestamp())
                    AND c.allowed_account_ids @> ARRAY[m.account_id,m.destination_account_id])
                """,through,connectionId,lease.uidValidity(),lease.token())==1;
    }
    @Override public void failed(MailboxLease lease,String code){
        if(!Set.of("UIDVALIDITY_CHANGED","MAILBOX_POLL_FAILED").contains(code))throw new IllegalArgumentException("Invalid safe code");
        jdbc.update("UPDATE email_mailboxes SET error_code=? WHERE source_connection_id=? AND lease_token=?",code,connectionId,lease.token());
    }
    @Override public void release(MailboxLease lease){
        jdbc.update("UPDATE email_mailboxes SET lease_token=NULL,lease_until=NULL WHERE source_connection_id=? AND lease_token=?",connectionId,lease.token());
    }
}
