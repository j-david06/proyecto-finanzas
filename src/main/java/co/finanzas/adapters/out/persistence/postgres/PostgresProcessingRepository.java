package co.finanzas.adapters.out.persistence.postgres;

import co.finanzas.application.ingestion.model.EncryptedPayload;
import co.finanzas.application.processing.model.*;
import co.finanzas.application.processing.port.out.ProcessingRepository;
import co.finanzas.domain.account.Account;
import co.finanzas.domain.shared.OwnerId;
import co.finanzas.domain.transaction.Transaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import static co.finanzas.application.processing.model.ProcessingFailure.Code.*;

/** Short claim transaction; financial insert and fenced acknowledgement share a second transaction. */
public final class PostgresProcessingRepository implements ProcessingRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int maxAttempts;
    private final int leaseSeconds;
    private final int retrySeconds;
    public PostgresProcessingRepository(JdbcTemplate jdbc, TransactionTemplate transactions,
                                        int maxAttempts, int leaseSeconds, int retrySeconds) {
        if (maxAttempts < 1 || maxAttempts > 20 || leaseSeconds < 30 || leaseSeconds > 3600
                || retrySeconds < 1 || retrySeconds > 3600) throw new IllegalArgumentException("Invalid worker limits");
        this.jdbc = jdbc; this.transactions = transactions; this.maxAttempts = maxAttempts;
        this.leaseSeconds = leaseSeconds; this.retrySeconds = retrySeconds;
    }

    @Override public Optional<IngestionWork> claim() {
        return transactions.execute(ignored -> {
            jdbc.update("""
                    WITH exhausted AS (
                        SELECT id FROM ingestion_logs WHERE source IN ('IOS_SHORTCUTS','IMAP') AND attempts>=?
                          AND ((state IN ('RECEIVED','RETRY') AND available_at<=now())
                            OR (state='PROCESSING' AND lease_until<=now()))
                        ORDER BY received_at,id LIMIT 100 FOR UPDATE SKIP LOCKED
                    ) UPDATE ingestion_logs l SET state='DEAD', lease_token=NULL, lease_until=NULL,
                        error_code=coalesce(error_code,'MAX_ATTEMPTS_EXCEEDED'), updated_at=now()
                      FROM exhausted e WHERE l.id=e.id
                    """, maxAttempts);
            return jdbc.query("""
                    WITH candidate AS (
                        SELECT id FROM ingestion_logs WHERE source IN ('IOS_SHORTCUTS','IMAP') AND attempts<?
                          AND ((state IN ('RECEIVED','RETRY') AND available_at<=now())
                            OR (state='PROCESSING' AND lease_until<=now()))
                        ORDER BY available_at,received_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    ) UPDATE ingestion_logs l SET state='PROCESSING', attempts=attempts+1,
                        lease_token=?, lease_until=now()+make_interval(secs=>?), updated_at=now()
                      FROM candidate c WHERE l.id=c.id RETURNING l.*
                    """, (rs, row) -> new IngestionWork(rs.getObject("id", UUID.class),
                    new OwnerId(rs.getObject("owner_id", UUID.class)), rs.getObject("source_connection_id", UUID.class),
                    rs.getString("idempotency_key"), new EncryptedPayload(rs.getBytes("payload_encrypted"), rs.getString("payload_key_id")),
                    rs.getBytes("payload_sha256"), rs.getObject("lease_token", UUID.class), rs.getInt("attempts"),rs.getString("source")),
                    maxAttempts, UUID.randomUUID(), leaseSeconds).stream().findFirst();
        });
    }

    @Override public Account account(IngestionWork work, UUID accountId) {
        return jdbc.query("SELECT * FROM accounts WHERE id=? AND owner_id=?",
                (rs, row) -> mapAccount(rs), accountId, work.owner().value()).stream().findFirst()
                .orElseThrow(() -> new ProcessingFailure(ACCOUNT_NOT_AUTHORIZED, false));
    }

    @Override public boolean complete(IngestionWork work, Transaction transaction, String parserVersion) {
        if (!transaction.id().equals(work.id()) || !transaction.ownerId().equals(work.owner())) {
            throw new IllegalArgumentException("Work identity mismatch");
        }
        return Boolean.TRUE.equals(transactions.execute(ignored -> {
            if (!lockLease(work)) return false;
            var connection = jdbc.query("SELECT * FROM source_connections WHERE id=? AND owner_id=? FOR SHARE",
                    (rs, row) -> PostgresSourceConnections.map(rs), work.connectionId(), work.owner().value())
                    .stream().findFirst().orElseThrow(() -> new ProcessingFailure(ACCOUNT_NOT_AUTHORIZED, false));
            Boolean unexpired = jdbc.queryForObject("SELECT expires_at IS NULL OR expires_at>clock_timestamp() FROM source_connections WHERE id=?",
                    Boolean.class, work.connectionId());
            if (!connection.active() || !connection.source().equals(work.source())
                    || (!work.source().equals("IMAP") && !connection.authenticationProfile().equals("API_KEY_ONLY")) || !Boolean.TRUE.equals(unexpired)
                    || !connection.allowedAccounts().contains(transaction.account().id())
                    || (transaction.counterpartyAccount()!=null && !connection.allowedAccounts().contains(transaction.counterpartyAccount().id()))) {
                throw new ProcessingFailure(ACCOUNT_NOT_AUTHORIZED, false);
            }
            UUID destinationId = transaction.counterpartyAccount()==null ? null : transaction.counterpartyAccount().id();
            var locked = jdbc.query("SELECT * FROM accounts WHERE owner_id=? AND id IN (?,?) ORDER BY id FOR SHARE",
                    (rs,row)->mapAccount(rs),work.owner().value(),transaction.account().id(),destinationId);
            var current = locked.stream().filter(a->a.id().equals(transaction.account().id())).findFirst()
                    .orElseThrow(() -> new ProcessingFailure(ACCOUNT_NOT_AUTHORIZED, false));
            if (!current.active() || !current.currency().equals(transaction.amount().currency())) {
                throw new ProcessingFailure(ACCOUNT_NOT_AUTHORIZED, false);
            }
            if(destinationId!=null) {
                var destination=locked.stream().filter(a->a.id().equals(destinationId)).findFirst()
                        .orElseThrow(()->new ProcessingFailure(ACCOUNT_NOT_AUTHORIZED,false));
                if(!destination.active() || !destination.currency().equals(transaction.amount().currency()))
                    throw new ProcessingFailure(ACCOUNT_NOT_AUTHORIZED,false);
                if(current.kind()!=Account.Kind.DEPOSIT || destination.kind()!=Account.Kind.CREDIT_CARD
                        || current.bankReference()==null || !current.bankReference().equals(transaction.metadata().originProduct())
                        || destination.cardLast4()==null || !destination.cardLast4().equals(transaction.metadata().cardLast4()))
                    throw new ProcessingFailure(ACCOUNT_MAPPING_REQUIRED,false);
            }
            transaction.requireIngestible();
            var metadata=transaction.metadata();
            jdbc.update("""
                    INSERT INTO transactions(id,owner_id,account_id,kind,status,amount,currency,occurred_at,
                        merchant,bank_provider,bank_event_id,counterparty_account_id,original_amount,original_currency,
                        tax_4x1000,card_last4,installments,origin_product,next_cut_on)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT DO NOTHING
                    """, transaction.id(), work.owner().value(), current.id(), transaction.kind().name(),transaction.status().name(),
                    transaction.amount().amount(), transaction.amount().currency().getCurrencyCode(),
                    Timestamp.from(transaction.occurredAt()), transaction.merchant(), transaction.bankProvider(), transaction.bankEventId(),
                    destinationId,metadata.originalAmount()==null?null:metadata.originalAmount().amount(),
                    metadata.originalAmount()==null?null:metadata.originalAmount().currency().getCurrencyCode(),
                    metadata.tax4x1000(),metadata.cardLast4(),metadata.installments(),metadata.originProduct(),metadata.nextCutOn());
            var matches = jdbc.query("""
                    SELECT * FROM transactions WHERE owner_id=? AND account_id=?
                      AND (id=? OR (bank_provider=? AND bank_event_id=?)) FOR SHARE
                    """, (rs, row) -> {
                boolean same = rs.getString("kind").equals(transaction.kind().name()) && rs.getString("status").equals(transaction.status().name())
                        && rs.getBigDecimal("amount").compareTo(transaction.amount().amount()) == 0
                        && rs.getString("currency").equals(transaction.amount().currency().getCurrencyCode())
                        && rs.getTimestamp("occurred_at").toInstant().equals(transaction.occurredAt())
                        && Objects.equals(rs.getString("merchant"), transaction.merchant())
                        && Objects.equals(rs.getString("bank_provider"), transaction.bankProvider())
                        && Objects.equals(rs.getString("bank_event_id"), transaction.bankEventId())
                        && Objects.equals(rs.getObject("counterparty_account_id",UUID.class),destinationId)
                        && sameDecimal(rs.getBigDecimal("original_amount"),metadata.originalAmount()==null?null:metadata.originalAmount().amount())
                        && Objects.equals(rs.getString("original_currency"),metadata.originalAmount()==null?null:metadata.originalAmount().currency().getCurrencyCode())
                        && sameDecimal(rs.getBigDecimal("tax_4x1000"),metadata.tax4x1000())
                        && Objects.equals(rs.getString("card_last4"),metadata.cardLast4())
                        && Objects.equals(rs.getObject("installments",Integer.class),metadata.installments())
                        && Objects.equals(rs.getString("origin_product"),metadata.originProduct())
                        && Objects.equals(rs.getObject("next_cut_on",java.time.LocalDate.class),metadata.nextCutOn());
                if (!same) throw new ProcessingFailure(BANK_EVENT_CONFLICT, false);
                return rs.getObject("id", UUID.class);
            }, work.owner().value(), current.id(), transaction.id(), transaction.bankProvider(), transaction.bankEventId());
            if (matches.size() != 1) throw new ProcessingFailure(BANK_EVENT_CONFLICT, false);
            // Check the deadline again after waiting on unique indexes. A stale attempt rolls back its insert.
            int updated = jdbc.update("""
                    UPDATE ingestion_logs SET state='PROCESSED',transaction_id=?,parser_version=?,error_code=NULL,
                        lease_token=NULL,lease_until=NULL,updated_at=now()
                    WHERE id=? AND lease_token=? AND state='PROCESSING' AND lease_until>clock_timestamp()
                    """, matches.getFirst(), parserVersion, work.id(), work.leaseToken());
            if (updated != 1) { ignored.setRollbackOnly(); return false; }
            return true;
        }));
    }

    @Override public boolean fail(IngestionWork work, ProcessingFailure failure) {
        String next = failure.retryable() ? (work.attempts() >= maxAttempts ? "DEAD" : "RETRY") : "REVIEW";
        long delay = Math.min(3600L, retrySeconds * (1L << Math.min(12, work.attempts() - 1)));
        return jdbc.update("""
                UPDATE ingestion_logs SET state=?,error_code=?,lease_token=NULL,lease_until=NULL,
                    available_at=now()+make_interval(secs=>?),updated_at=now()
                WHERE id=? AND owner_id=? AND state='PROCESSING' AND lease_token=? AND lease_until>clock_timestamp()
                """, next, failure.code().name(), delay, work.id(), work.owner().value(), work.leaseToken()) == 1;
    }

    private boolean lockLease(IngestionWork work) {
        return !jdbc.query("""
                SELECT id FROM ingestion_logs WHERE id=? AND owner_id=? AND state='PROCESSING'
                  AND lease_token=? AND lease_until>clock_timestamp() FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), work.id(), work.owner().value(), work.leaseToken()).isEmpty();
    }

    private static Account mapAccount(ResultSet rs) throws SQLException {
        return new Account(rs.getObject("id", UUID.class), new OwnerId(rs.getObject("owner_id", UUID.class)),
                rs.getString("name"), Account.Kind.valueOf(rs.getString("kind")), Currency.getInstance(rs.getString("currency")), rs.getBoolean("active"),
                rs.getString("bank_reference"),rs.getString("card_last4"));
    }
    private static boolean sameDecimal(java.math.BigDecimal a,java.math.BigDecimal b) {
        return a==null ? b==null : b!=null && a.compareTo(b)==0;
    }
}
