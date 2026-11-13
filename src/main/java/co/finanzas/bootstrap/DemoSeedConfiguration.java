package co.finanzas.bootstrap;

import co.finanzas.application.ingestion.port.out.DigestPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

/** Explicit local demo provisioning; never runs in the ordinary API/worker profiles. */
@Configuration(proxyBeanMethods = false)
@Profile("api & demo & !worker")
@ConditionalOnProperty(name = "finance.demo.seed-enabled", havingValue = "true")
public class DemoSeedConfiguration {
    @Bean
    ApplicationRunner demoSeed(JdbcTemplate jdbc, PlatformTransactionManager manager, DigestPort digests,
                               @Value("${spring.datasource.url}") String databaseUrl,
                               @Value("${server.address}") String serverAddress) {
        return args -> {
            if (!databaseUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/finanzas_demo")
                    || !serverAddress.equals("127.0.0.1")
                    || !"finanzas_demo".equals(jdbc.queryForObject("SELECT current_database()", String.class))
                    || !"public".equals(jdbc.queryForObject("SELECT current_schema()", String.class))) {
                throw new IllegalStateException("Demo provisioning requires loopback and the dedicated finanzas_demo database");
            }
            Path credentials = Path.of(".env.demo-panel");
            if (Files.exists(credentials, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(credentials, LinkOption.NOFOLLOW_LINKS) || Files.size(credentials) > 8192) {
                    throw new IllegalStateException("Invalid local demo credential file");
                }
                var properties = new Properties();
                try (var reader = Files.newBufferedReader(credentials, StandardCharsets.UTF_8)) { properties.load(reader); }
                String key = properties.getProperty("INGESTION_API_KEY", "");
                UUID connection;
                UUID account;
                try {
                    connection = UUID.fromString(properties.getProperty("SOURCE_CONNECTION_ID", ""));
                    account = UUID.fromString(properties.getProperty("INGESTION_ACCOUNT_ID", ""));
                } catch (IllegalArgumentException exception) { throw new IllegalStateException("Invalid local demo identifiers"); }
                if (!key.startsWith("pf_ing_" + connection + ".") || key.length() > 180) {
                    throw new IllegalStateException("Invalid local demo credential");
                }
                Integer matches = jdbc.queryForObject("""
                        SELECT count(*) FROM source_connections c JOIN accounts a ON a.owner_id=c.owner_id AND a.id=?
                        WHERE c.id=? AND c.source='IOS_SHORTCUTS' AND c.name='DEMO_PANEL_LOCAL'
                          AND c.authentication_profile='API_KEY_ONLY' AND c.active AND a.active
                          AND (c.expires_at IS NULL OR c.expires_at>now())
                          AND c.allowed_account_ids @> ARRAY[?]::uuid[] AND c.api_key_sha256=?
                        """, Integer.class, account, connection, account, digests.sha256(key.getBytes(StandardCharsets.UTF_8)));
                if (matches == null || matches != 1) {
                    throw new IllegalStateException("Demo credentials do not match finanzas_demo; review .env.demo-panel before restarting");
                }
                return;
            }
            UUID owner = UUID.randomUUID(), account = UUID.randomUUID(), connection = UUID.randomUUID();
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            String key = "pf_ing_" + connection + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            java.util.Arrays.fill(secret, (byte) 0);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("INSERT INTO accounts(id,owner_id,name,kind) VALUES (?,?,'Cuenta de demostración','DEPOSIT')", account, owner);
                jdbc.update("""
                        INSERT INTO source_connections(id,owner_id,source,name,api_key_sha256,allowed_account_ids)
                        VALUES (?,?,'IOS_SHORTCUTS','DEMO_PANEL_LOCAL',?,ARRAY[?]::uuid[])
                        """, connection, owner, digests.sha256(key.getBytes(StandardCharsets.UTF_8)), account);
                try {
                    Files.writeString(credentials, "INGESTION_API_KEY=" + key + "\nSOURCE_CONNECTION_ID=" + connection
                            + "\nINGESTION_ACCOUNT_ID=" + account + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Cannot save local demo credentials", exception);
                }
            });
        };
    }
}
