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
                HttpRequest panel = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + web.getWebServer().getPort() + "/demo/index.html")).GET().build();
                assertEquals(401, client.send(panel, HttpResponse.BodyHandlers.discarding()).statusCode());
            }
        });
    }

    @Test
    void workerStartsWithoutHttpServer() throws Exception {
        withProfile("worker", context -> assertFalse(context instanceof WebServerApplicationContext));
    }

    @Test
    void demoProfileServesLocalPanelWithStrictHeadersAndKeepsApiAuthentication() throws Exception {
        withProfile("api,demo", context -> {
            WebServerApplicationContext web = assertInstanceOf(WebServerApplicationContext.class, context);
            String base = "http://127.0.0.1:" + web.getWebServer().getPort();
            try (HttpClient client = HttpClient.newHttpClient()) {
                var panel = client.send(HttpRequest.newBuilder(URI.create(base + "/demo/index.html")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, panel.statusCode());
                assertTrue(panel.body().contains("Nuevo movimiento"));
                assertTrue(panel.headers().firstValue("Content-Security-Policy").orElseThrow().contains("connect-src 'self'"));
                assertFalse(panel.headers().firstValue("Content-Security-Policy").orElseThrow().contains("unsafe-inline"));
                assertEquals("no-referrer", panel.headers().firstValue("Referrer-Policy").orElseThrow());
                for (String path : new String[]{"app.js", "model.mjs", "styles.css", "icon.svg"}) {
                    var asset = client.send(HttpRequest.newBuilder(URI.create(base + "/demo/" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, asset.statusCode(), path);
                    if (path.endsWith("js")) assertTrue(asset.headers().firstValue("Content-Type").orElseThrow().contains("javascript"), path);
                }
                var denied = client.send(HttpRequest.newBuilder(URI.create(base + "/api/v1/transactions/ingest"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(401, denied.statusCode());
                var redirect = client.send(HttpRequest.newBuilder(URI.create(base + "/demo")).GET().build(), HttpResponse.BodyHandlers.discarding());
                assertEquals(302, redirect.statusCode());
                assertEquals("/demo/index.html", URI.create(redirect.headers().firstValue("Location").orElseThrow()).getPath());
            }
        });
    }

    private void withProfile(String profile, ContextAssertion assertion) throws Exception {
        String schema = "finanzas_boot_" + UUID.randomUUID().toString().replace("-", "");
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(FinanceApplication.class)
                .profiles(profile.split(",")).run(
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
