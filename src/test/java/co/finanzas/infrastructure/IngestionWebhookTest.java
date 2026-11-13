package co.finanzas.infrastructure;

import co.finanzas.bootstrap.FinanceApplication;
import co.finanzas.application.ingestion.model.IngestionProblem;
import co.finanzas.application.ingestion.port.in.AuthenticateApiKeyUseCase;
import co.finanzas.application.ingestion.port.in.ReceiveIngestionUseCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionWebhookTest {
    private final String schema = "finanzas_webhook_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID owner = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID otherAccount = UUID.randomUUID();
    private final UUID connection = UUID.randomUUID();
    private final String key = "pf_ing_" + connection + "." + "k".repeat(43);
    private final ObjectMapper mapper = new ObjectMapper();
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private HttpClient client;
    private String baseUrl;

    @BeforeAll void start() {
        context = new SpringApplicationBuilder(FinanceApplication.class).profiles("api").run(
                "--spring.datasource.url=" + System.getenv("FINANCE_TEST_DB_URL"),
                "--spring.datasource.username=" + System.getenv().getOrDefault("FINANCE_TEST_DB_USER", "finance_test"),
                "--spring.datasource.password=" + System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD", ""),
                "--spring.datasource.hikari.connection-init-sql=SET search_path TO " + schema,
                "--spring.flyway.schemas=" + schema, "--spring.flyway.default-schema=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--server.port=0", "--server.address=127.0.0.1",
                "--finance.ingestion.require-https=false",
                "--finance.ingestion.payload-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        jdbc = context.getBean(JdbcTemplate.class);
        baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @BeforeEach void fixtures() throws Exception {
        // Refuse any fixture deletion unless the connection is in our isolated random schema.
        assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
        jdbc.update("DELETE FROM ingestion_logs");
        jdbc.update("DELETE FROM source_connections");
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("INSERT INTO accounts(id, owner_id, name, kind) VALUES (?, ?, 'Nu', 'DEPOSIT')", account, owner);
        jdbc.update("INSERT INTO accounts(id, owner_id, name, kind) VALUES (?, ?, 'Otra', 'CASH')", otherAccount, UUID.randomUUID());
        jdbc.update("""
                INSERT INTO source_connections(id, owner_id, source, name, api_key_sha256, allowed_account_ids)
                VALUES (?, ?, 'IOS_SHORTCUTS', 'Test iPhone', ?, ARRAY[?]::uuid[])
                """, connection, owner, hash(key.getBytes(StandardCharsets.UTF_8)), account);
    }

    @AfterAll void close() throws Exception {
        if (client != null) client.close();
        if (context != null) context.close();
        if (!schema.matches("finanzas_webhook_[a-f0-9]{32}")) throw new IllegalStateException("Invalid test schema");
        try (var cleanup = SchemaMigrationTest.open(); var statement = cleanup.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void receivesDurablyEncryptsAndReturnsSameIdForRetry() throws Exception {
        UUID event = UUID.randomUUID();
        String body = body(account);
        var first = send(key, event, body);
        assertEquals(202, first.statusCode(), first.body());
        var retry = send(key, event, body);
        assertEquals(first.body(), retry.body());
        assertEquals(202, retry.statusCode());
        assertEquals(1, count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transactions", Integer.class));
        assertEquals("no-store", first.headers().firstValue("Cache-Control").orElseThrow());
        byte[] encrypted = jdbc.queryForObject("SELECT payload_encrypted FROM ingestion_logs", byte[].class);
        assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains("Comercio"));
        ByteBuffer envelope = ByteBuffer.wrap(encrypted);
        assertEquals(1, envelope.get());
        byte[] nonce = new byte[12];
        envelope.get(nonce);
        byte[] ciphertext = new byte[envelope.remaining()];
        envelope.get(ciphertext);
        var decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(new byte[32], "AES"), new GCMParameterSpec(128, nonce));
        decrypt.updateAAD((owner + ":" + connection + ":" + event).getBytes(StandardCharsets.UTF_8));
        assertEquals(body, new String(decrypt.doFinal(ciphertext), StandardCharsets.UTF_8));
        assertArrayEquals(hash(body.getBytes(StandardCharsets.UTF_8)),
                jdbc.queryForObject("SELECT payload_sha256 FROM ingestion_logs", byte[].class));
    }

    @Test void conflictingPayloadCannotOverwriteAcceptedEvent() throws Exception {
        UUID event = UUID.randomUUID();
        assertEquals(202, send(key, event, body(account)).statusCode());
        var conflict = send(key, event, body(account).replace("Comercio", "Diferente"));
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, count());
    }

    @Test void concurrentRetriesCreateExactlyOneRow() throws Exception {
        UUID event = UUID.randomUUID();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> { start.await(); return send(key, event, body(account)); }));
            start.countDown();
            Set<String> ids = new HashSet<>();
            for (var future : futures) {
                var response = future.get(20, TimeUnit.SECONDS);
                assertEquals(202, response.statusCode(), response.body());
                ids.add(mapper.readTree(response.body()).get("ingestion_id").asText());
            }
            assertEquals(1, ids.size());
            assertEquals(1, count());
        }
    }

    @Test void rejectsMissingBadRevokedExpiredAndHmacCredentials() throws Exception {
        assertEquals(401, send(null, UUID.randomUUID(), body(account)).statusCode());
        assertEquals(401, send(key + "bad", UUID.randomUUID(), body(account)).statusCode());
        for (String update : List.of("active=false", "expires_at=now()-interval '1 second', created_at=now()-interval '1 day'",
                "authentication_profile='HMAC_REQUIRED'")) {
            jdbc.update("UPDATE source_connections SET active=true, expires_at=NULL, authentication_profile='API_KEY_ONLY'");
            jdbc.update("UPDATE source_connections SET " + update);
            assertEquals(401, send(key, UUID.randomUUID(), body(account)).statusCode(), update);
        }
        assertEquals(0, count());
    }

    @Test void rejectsForeignUnlistedInactiveAndWrongCurrencyAccounts() throws Exception {
        assertEquals(403, send(key, UUID.randomUUID(), body(otherAccount)).statusCode());
        UUID unlisted = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts(id,owner_id,name,kind) VALUES (?,?,'Sin permiso','CASH')", unlisted, owner);
        assertEquals(403, send(key, UUID.randomUUID(), body(unlisted)).statusCode());
        jdbc.update("UPDATE accounts SET active=false WHERE id=?", account);
        assertEquals(403, send(key, UUID.randomUUID(), body(account)).statusCode());
        jdbc.update("UPDATE accounts SET active=true WHERE id=?", account);
        assertEquals(422, send(key, UUID.randomUUID(), body(account).replace("\"COP\"", "\"USD\"")).statusCode());
        assertEquals(0, count());
    }

    @Test void rechecksRevocationInsideAdmissionTransaction() {
        var principal = context.getBean(AuthenticateApiKeyUseCase.class).authenticate(key);
        jdbc.update("UPDATE source_connections SET active=false WHERE id=?", connection);
        assertThrows(IngestionProblem.class, () -> context.getBean(ReceiveIngestionUseCase.class)
                .receive(principal, UUID.randomUUID(), account, null, body(account).getBytes(StandardCharsets.UTF_8)));
        assertEquals(0, count());
    }

    @Test void validatesJsonAndLimitsBeforePersisting() throws Exception {
        assertEquals(422, send(key, UUID.randomUUID(), body(account).replace("\"45900.00\"", "45900.00")).statusCode());
        assertEquals(422, send(key, UUID.randomUUID(), body(account).replace("45900.00", "0.001")).statusCode());
        assertEquals(400, send(key, UUID.randomUUID(), "{bad").statusCode());
        assertEquals(400, send(key, UUID.randomUUID(), body(account).replace("\"schema_version\":1", "\"schema_version\":1,\"owner_id\":\"forged\"")).statusCode());
        assertEquals(413, send(key, UUID.randomUUID(), "x".repeat(65537)).statusCode());
        assertEquals(0, count());
    }

    @Test void rawSmsIsQueuedAndNeverInventsAMovement() throws Exception {
        String raw = """
                {"schema_version":1,"trigger":"SMS","format":"RAW_MESSAGE","account_id":"%s",
                "message":{"text":"Mensaje transaccional de prueba","received_at":"2026-11-13T13:46:00-05:00"}}
                """.formatted(account);
        assertEquals(202, send(key, UUID.randomUUID(), raw).statusCode());
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT state FROM ingestion_logs", String.class));
    }

    @Test void pollingIsScopedToSameConnectionAndOwner() throws Exception {
        var received = send(key, UUID.randomUUID(), body(account));
        String location = received.headers().firstValue("Location").orElseThrow();
        var own = get(location, key);
        assertEquals(200, own.statusCode());
        assertEquals(received.body(), own.body());
        UUID foreignOwner = UUID.randomUUID(), foreignAccount = UUID.randomUUID(), foreignConnection = UUID.randomUUID();
        String foreignKey = "pf_ing_" + foreignConnection + "." + "f".repeat(43);
        jdbc.update("INSERT INTO accounts(id,owner_id,name,kind) VALUES (?,?,'Foreign','CASH')", foreignAccount, foreignOwner);
        jdbc.update("""
                INSERT INTO source_connections(id,owner_id,source,name,api_key_sha256,allowed_account_ids)
                VALUES (?,?,'IOS_SHORTCUTS','Foreign',?,ARRAY[?]::uuid[])
                """, foreignConnection, foreignOwner, hash(foreignKey.getBytes(StandardCharsets.UTF_8)), foreignAccount);
        assertEquals(404, get(location, foreignKey).statusCode());
        assertEquals(404, get("/api/v1/ingestions/" + UUID.randomUUID(), key).statusCode());
    }

    @Test void terminalReplayReturns200AndKeyCannotAccessOtherRoutes() throws Exception {
        UUID event = UUID.randomUUID();
        send(key, event, body(account));
        jdbc.update("UPDATE ingestion_logs SET state='REVIEW'");
        assertEquals(200, send(key, event, body(account)).statusCode());
        assertTrue(get("/api/v1/accounts", key).statusCode() >= 400);
    }

    private int count() { return jdbc.queryForObject("SELECT count(*) FROM ingestion_logs", Integer.class); }
    private HttpResponse<String> send(String apiKey, UUID event, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/transactions/ingest"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Idempotency-Key", event.toString());
        if (apiKey != null) builder.header("X-API-Key", apiKey);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> get(String path, String apiKey) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(5))
                .header("X-API-Key", apiKey).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private String body(UUID accountId) {
        return """
                {"schema_version":1,"trigger":"APPLE_PAY","format":"NORMALIZED","account_id":"%s",
                "movement":{"kind":"EXPENSE","amount":"45900.00","currency":"COP",
                "occurred_at":"2026-11-13T13:45:12-05:00","merchant":"Comercio","bank_provider":"NU_CO"}}
                """.formatted(accountId);
    }
    private byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
}
