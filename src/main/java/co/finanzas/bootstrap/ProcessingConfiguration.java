package co.finanzas.bootstrap;

import co.finanzas.adapters.in.worker.IngestionProcessingJob;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.adapters.out.parser.NormalizedMovementParser;
import co.finanzas.adapters.out.persistence.postgres.PostgresProcessingRepository;
import co.finanzas.application.ingestion.port.out.DigestPort;
import co.finanzas.application.processing.port.in.ProcessIngestionUseCase;
import co.finanzas.application.processing.port.out.*;
import co.finanzas.application.processing.service.ProcessIngestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("worker | (api & demo)")
public class ProcessingConfiguration {
    @Bean ProcessingRepository processingRepository(JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${finance.processing.max-attempts:5}") int attempts,
            @Value("${finance.processing.lease-seconds:90}") int lease,
            @Value("${finance.processing.retry-seconds:10}") int retry) {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        return new PostgresProcessingRepository(jdbc, tx, attempts, lease, retry);
    }
    @Bean MovementParserPort movementParser(IngestionRequestDecoder decoder) { return new NormalizedMovementParser(decoder); }
    @Bean ProcessIngestionUseCase processIngestion(ProcessingRepository repository, PayloadDecryptionPort cipher,
                                                   MovementParserPort parser, DigestPort digests) {
        return new ProcessIngestionService(repository, cipher, parser, digests);
    }
    @Configuration(proxyBeanMethods = false)
    @Profile("worker | (api & demo)")
    @EnableScheduling
    @ConditionalOnProperty(name = "finance.processing.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
        @Bean IngestionProcessingJob ingestionProcessingJob(ProcessIngestionUseCase processor,
                @Value("${finance.processing.batch-size:20}") int batchSize) {
            return new IngestionProcessingJob(processor, batchSize);
        }
    }
}
