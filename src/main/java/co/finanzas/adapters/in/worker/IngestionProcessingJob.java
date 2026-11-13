package co.finanzas.adapters.in.worker;

import co.finanzas.application.processing.port.in.ProcessIngestionUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class IngestionProcessingJob {
    private static final Logger LOG = LoggerFactory.getLogger(IngestionProcessingJob.class);
    private final ProcessIngestionUseCase processor;
    private final int batchSize;
    public IngestionProcessingJob(ProcessIngestionUseCase processor, int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid worker batch size");
        this.processor = processor; this.batchSize = batchSize;
    }
    @Scheduled(fixedDelayString = "${finance.processing.poll-delay-ms:2000}", initialDelayString = "${finance.processing.poll-delay-ms:2000}")
    public void poll() {
        try {
            for (int i = 0; i < batchSize && !Thread.currentThread().isInterrupted(); i++) {
                if (!processor.processNext()) break;
            }
        } catch (RuntimeException exception) {
            // No payload, credential, SQL or exception message in worker logs.
            LOG.warn("Ingestion polling interrupted: PROCESSING_STORAGE_UNAVAILABLE");
        }
    }
}
