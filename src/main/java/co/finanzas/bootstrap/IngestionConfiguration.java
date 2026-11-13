package co.finanzas.bootstrap;

import co.finanzas.adapters.out.persistence.postgres.PostgresIngestionRepository;
import co.finanzas.adapters.out.persistence.postgres.PostgresSourceConnections;
import co.finanzas.application.ingestion.port.in.AuthenticateApiKeyUseCase;
import co.finanzas.application.ingestion.port.in.ReceiveIngestionUseCase;
import co.finanzas.application.ingestion.port.out.*;
import co.finanzas.application.ingestion.service.ApiKeyAuthenticationService;
import co.finanzas.application.ingestion.service.ReceiveIngestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("api & !worker")
public class IngestionConfiguration {
    @Bean SourceConnectionRepository sourceConnections(JdbcTemplate jdbc) { return new PostgresSourceConnections(jdbc); }
    @Bean IngestionRepository ingestionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
                                                  DigestPort digests, Clock clock) {
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
        return new PostgresIngestionRepository(jdbc, transaction, digests, clock);
    }
    @Bean AuthenticateApiKeyUseCase authenticateApiKey(SourceConnectionRepository connections, DigestPort digests, Clock clock,
            @Value("${finance.ingestion.authentication-profile}") String profile) {
        if (!profile.equals("API_KEY_ONLY")) throw new IllegalStateException("Only API_KEY_ONLY is implemented");
        return new ApiKeyAuthenticationService(connections, digests, clock);
    }
    @Bean ReceiveIngestionUseCase receiveIngestion(IngestionRepository repository, DigestPort digests, PayloadCipherPort cipher) {
        return new ReceiveIngestionService(repository, digests, cipher);
    }
}
