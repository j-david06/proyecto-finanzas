package co.finanzas.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real PostgreSQL tests. Always uses an isolated randomly named schema. */
@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DB_URL", matches = ".+")
class SchemaMigrationTest {
    private static final String SCHEMA = "finanzas_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID OTHER_OWNER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID OTHER_ACCOUNT = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private Connection connection;

    static Connection open() throws SQLException {
        return DriverManager.getConnection(System.getenv("FINANCE_TEST_DB_URL"),
                System.getenv().getOrDefault("FINANCE_TEST_DB_USER", "finance_test"),
                System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD", ""));
    }

    @BeforeAll
    static void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(System.getenv("FINANCE_TEST_DB_URL"),
                        System.getenv().getOrDefault("FINANCE_TEST_DB_USER", "finance_test"),
                        System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD", ""))
                .schemas(SCHEMA).defaultSchema(SCHEMA).load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertTrue(flyway.validateWithResult().validationSuccessful);
    }

    @BeforeEach
    void fixtures() throws SQLException {
        connection = open();
        connection.setAutoCommit(false);
        execute("SET search_path TO " + SCHEMA);
        execute("INSERT INTO accounts(id, owner_id, name, kind) VALUES ('" + ACCOUNT + "','" + OWNER + "','Nu','DEPOSIT')");
        execute("INSERT INTO accounts(id, owner_id, name, kind) VALUES ('" + OTHER_ACCOUNT + "','" + OTHER_OWNER + "','Otra','CASH')");
        execute(connectionInsert(CONNECTION, OWNER, ACCOUNT));
    }

    @AfterEach
    void rollback() throws SQLException {
        if (connection != null) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    @AfterAll
    static void removeIsolatedSchema() throws SQLException {
        if (!SCHEMA.matches("finanzas_test_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test schema");
        }
        try (Connection cleanup = open(); var statement = cleanup.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @Test
    void apiKeyProfileDefaultsAndHashMustBeSha256Length() throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT authentication_profile FROM source_connections WHERE id='" + CONNECTION + "'")) {
            assertTrue(result.next());
            assertEquals("API_KEY_ONLY", result.getString(1));
        }
        rejects("23514", "UPDATE source_connections SET api_key_sha256 = decode('ff', 'hex') WHERE id='" + CONNECTION + "'");
    }

    @Test
    void accountAllowlistRejectsForeignOwnershipDuplicatesAndDanglingReferences() throws SQLException {
        rejects("23503", connectionInsert(UUID.randomUUID(), OWNER, OTHER_ACCOUNT));
        rejects("23514", "UPDATE source_connections SET allowed_account_ids=ARRAY['" + ACCOUNT + "'::uuid,'" + ACCOUNT + "'::uuid]");
        rejects("23503", "DELETE FROM accounts WHERE id='" + ACCOUNT + "'");
        rejects("23514", "UPDATE accounts SET owner_id='" + OTHER_OWNER + "' WHERE id='" + ACCOUNT + "'");
        rejects("23514", "UPDATE source_connections SET owner_id='" + OTHER_OWNER + "' WHERE id='" + CONNECTION + "'");
    }

    @Test
    void ingestionIsIdempotentAndConnectionOwnerAndSourceCannotBeForged() throws SQLException {
        execute(ingestionInsert(UUID.randomUUID(), OWNER, "IOS_SHORTCUTS", "same-event"));
        rejects("23505", ingestionInsert(UUID.randomUUID(), OWNER, "IOS_SHORTCUTS", "same-event"));
        rejects("23503", ingestionInsert(UUID.randomUUID(), OTHER_OWNER, "IOS_SHORTCUTS", "different-event"));
        rejects("23503", ingestionInsert(UUID.randomUUID(), OWNER, "GMAIL", "mail-event"));
    }

    @Test
    void processingRequiresLeaseAndProcessedRequiresMovement() throws SQLException {
        UUID id = UUID.randomUUID();
        execute(ingestionInsert(id, OWNER, "IOS_SHORTCUTS", "state-event"));
        rejects("23514", "UPDATE ingestion_logs SET state='PROCESSING' WHERE id='" + id + "'");
        rejects("23514", "UPDATE ingestion_logs SET state='PROCESSED' WHERE id='" + id + "'");
        execute("UPDATE ingestion_logs SET state='PROCESSING', lease_token='" + UUID.randomUUID()
                + "', lease_until=now()+interval '1 minute' WHERE id='" + id + "'");
    }

    @Test
    void bankReferenceIsUniqueAndMoneyMustBePositiveAndFinite() throws SQLException {
        execute(transactionInsert(UUID.randomUUID(), ACCOUNT, "10.00", "bank-1"));
        rejects("23505", transactionInsert(UUID.randomUUID(), ACCOUNT, "10.00", "bank-1"));
        rejects("23514", transactionInsert(UUID.randomUUID(), ACCOUNT, "NaN", "bank-2"));
        rejects("23514", transactionInsert(UUID.randomUUID(), ACCOUNT, "0", "bank-3"));
        rejects("23503", transactionInsert(UUID.randomUUID(), OTHER_ACCOUNT, "10", "bank-4"));
    }

    @Test
    void installmentsCannotExploitSqlNullCheckSemantics() throws SQLException {
        rejects("23514", "INSERT INTO recurring_charges(id,owner_id,account_id,name,kind,expected_amount,billing_day,starts_on,next_due_on)"
                + " VALUES ('" + UUID.randomUUID() + "','" + OWNER + "','" + ACCOUNT
                + "','Cuotas','INSTALLMENT',10,15,'2026-01-01','2026-01-15')");
    }

    private String connectionInsert(UUID id, UUID owner, UUID account) {
        return "INSERT INTO source_connections(id,owner_id,source,name,api_key_sha256,allowed_account_ids) VALUES ('"
                + id + "','" + owner + "','IOS_SHORTCUTS','iPhone',decode('"
                + id.toString().replace("-", "").repeat(2) + "','hex'),ARRAY['" + account + "'::uuid])";
    }

    private String ingestionInsert(UUID id, UUID owner, String source, String key) {
        return "INSERT INTO ingestion_logs(id,owner_id,source,source_connection_id,idempotency_key,payload_sha256,payload_encrypted,payload_key_id)"
                + " VALUES ('" + id + "','" + owner + "','" + source + "','" + CONNECTION + "','" + key
                + "',decode(repeat('aa',32),'hex'),decode('aa','hex'),'test-key')";
    }

    private String transactionInsert(UUID id, UUID account, String amount, String bankId) {
        return "INSERT INTO transactions(id,owner_id,account_id,kind,amount,occurred_at,bank_provider,bank_event_id)"
                + " VALUES ('" + id + "','" + OWNER + "','" + account
                + "','EXPENSE','" + amount + "',now(),'NU_CO','" + bankId + "')";
    }

    private void rejects(String state, String sql) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        SQLException exception = assertThrows(SQLException.class, () -> execute(sql));
        assertEquals(state, exception.getSQLState());
        connection.rollback(savepoint);
        connection.releaseSavepoint(savepoint);
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
