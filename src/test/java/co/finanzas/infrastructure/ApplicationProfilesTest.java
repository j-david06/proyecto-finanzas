package co.finanzas.infrastructure;

import co.finanzas.bootstrap.FinanceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FINANCE_TEST_DB_URL", matches = ".+")
class ApplicationProfilesTest {
    @Test
    void apiStartsMigratesAndDeniesUnauthenticatedRequests() throws Exception {
        withProfile("api", context -> {
            WebServerApplicationContext web = assertInstanceOf(WebServerApplicationContext.class, context);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                + web.getWebServer().getPort() + "/api/v1/transactions/ingest"))
                        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}")) .build();
                assertEquals(401, client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        });
    }

    @Test
    void workerStartsWithoutHttpServer() throws Exception {
        withProfile("worker", context -> assertFalse(context instanceof WebServerApplicationContext));
    }

    private void withProfile(String profile, ContextAssertion assertion) throws Exception {
        String schema = "finanzas_boot_" + UUID.randomUUID().toString().replace("-", "");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(FinanceApplication.class)
                .profiles(profile).run(
                        "--spring.datasource.url=" + System.getenv("FINANCE_TEST_DB_URL"),
                        "--spring.datasource.username=" + System.getenv().getOrDefault("FINANCE_TEST_DB_USER", "finance_test"),
                        "--spring.datasource.password=" + System.getenv().getOrDefault("FINANCE_TEST_DB_PASSWORD", ""),
                        "--spring.flyway.schemas=" + schema,
                        "--spring.flyway.default-schema=" + schema,
                        "--spring.jpa.properties.hibernate.default_schema=" + schema,
                        "--spring.main.keep-alive=false", "--server.port=0",
                        "--finance.ingestion.require-https=false",
                        "--finance.ingestion.payload-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")) {
            assertion.accept(context);
        } finally {
            if (!schema.matches("finanzas_boot_[a-f0-9]{32}")) {
                throw new IllegalStateException("Unexpected test schema");
            }
            try (var connection = SchemaMigrationTest.open(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @FunctionalInterface
    private interface ContextAssertion {
        void accept(ConfigurableApplicationContext context) throws Exception;
    }
}
