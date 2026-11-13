package co.finanzas.adapters.out.persistence.postgres;

import co.finanzas.application.ingestion.model.AuthenticatedSource;
import co.finanzas.application.ingestion.model.EncryptedPayload;
import co.finanzas.application.ingestion.model.IngestionProblem;
import co.finanzas.application.ingestion.model.IngestionReceipt;
import co.finanzas.application.ingestion.port.out.DigestPort;
import co.finanzas.application.ingestion.port.out.IngestionRepository;
import co.finanzas.domain.account.Account;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.OwnerId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static co.finanzas.application.ingestion.model.IngestionProblem.Code.*;

public final class PostgresIngestionRepository implements IngestionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DigestPort digests;
    private final Clock clock;

    public PostgresIngestionRepository(JdbcTemplate jdbc, TransactionTemplate transactions, DigestPort digests, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.digests = digests;
        this.clock = clock;
    }

    @Override public IngestionReceipt accept(AuthenticatedSource source, UUID eventId, UUID accountId,
                                             Money amount, byte[] hash, EncryptedPayload payload) {
        return transactions.execute(ignored -> {
            // Hold shared row locks until commit: revocation/allowlist changes cannot race admission.
            var connection = jdbc.query("SELECT * FROM source_connections WHERE id = ? FOR SHARE",
                    (rs, row) -> PostgresSourceConnections.map(rs), source.connectionId()).stream()
                    .findFirst().orElseThrow(() -> new IngestionProblem(UNAUTHORIZED));
            if (!connection.active() || !connection.source().equals("IOS_SHORTCUTS")
                    || !connection.authenticationProfile().equals("API_KEY_ONLY")
                    || !connection.ownerId().equals(source.ownerId())
                    || !digests.matches(connection.apiKeyHash(), source.apiKeyHash())
                    || (connection.expiresAt() != null && !connection.expiresAt().isAfter(clock.instant()))) {
                throw new IngestionProblem(UNAUTHORIZED);
            }
            if (!connection.allowedAccounts().contains(accountId)) throw new IngestionProblem(FORBIDDEN);
            var account = jdbc.query("SELECT * FROM accounts WHERE owner_id = ? AND id = ? FOR SHARE",
                    (rs, row) -> new Account(rs.getObject("id", UUID.class),
                            new OwnerId(rs.getObject("owner_id", UUID.class)), rs.getString("name"),
                            Account.Kind.valueOf(rs.getString("kind")), Currency.getInstance(rs.getString("currency")),
                            rs.getBoolean("active")), source.ownerId().value(), accountId).stream()
                    .findFirst().orElseThrow(() -> new IngestionProblem(FORBIDDEN));
            if (!account.active()) throw new IngestionProblem(FORBIDDEN);
            if (amount != null) {
                try { account.requireCompatible(source.ownerId(), amount); }
                catch (IllegalArgumentException exception) { throw new IngestionProblem(INVALID_MOVEMENT); }
            }
            var inserted = jdbc.query("""
                    INSERT INTO ingestion_logs(id, owner_id, source, source_connection_id, idempotency_key,
                        payload_sha256, payload_encrypted, payload_key_id)
                    VALUES (?, ?, 'IOS_SHORTCUTS', ?, ?, ?, ?, ?)
                    ON CONFLICT (owner_id, source, source_connection_id, idempotency_key) DO NOTHING
                    RETURNING id, state
                    """, (rs, row) -> new IngestionReceipt(rs.getObject("id", UUID.class), rs.getString("state")),
                    UUID.randomUUID(), source.ownerId().value(), source.connectionId(), eventId.toString(),
                    hash, payload.bytes(), payload.keyId());
            if (!inserted.isEmpty()) return inserted.getFirst();
            // READ COMMITTED obtains a new snapshot after waiting on the unique index conflict.
            return jdbc.query("""
                    SELECT id, state, payload_sha256, transaction_id, error_code, attempts FROM ingestion_logs
                    WHERE owner_id = ? AND source = 'IOS_SHORTCUTS' AND source_connection_id = ? AND idempotency_key = ?
                    """, (rs, row) -> {
                        if (!digests.matches(rs.getBytes("payload_sha256"), hash)) {
                            throw new IngestionProblem(IDEMPOTENCY_CONFLICT);
                        }
                        return receipt(rs);
                    }, source.ownerId().value(), source.connectionId(), eventId.toString()).getFirst();
        });
    }

    @Override public Optional<IngestionReceipt> find(AuthenticatedSource source, UUID id) {
        return jdbc.query("""
                SELECT id, state, transaction_id, error_code, attempts FROM ingestion_logs
                WHERE id = ? AND owner_id = ? AND source = 'IOS_SHORTCUTS' AND source_connection_id = ?
                """, (rs, row) -> receipt(rs),
                id, source.ownerId().value(), source.connectionId()).stream().findFirst();
    }

    private static IngestionReceipt receipt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new IngestionReceipt(rs.getObject("id", UUID.class), rs.getString("state"),
                rs.getObject("transaction_id", UUID.class), rs.getString("error_code"), rs.getInt("attempts"));
    }
}
