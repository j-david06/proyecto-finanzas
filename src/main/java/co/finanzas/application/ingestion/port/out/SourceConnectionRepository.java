package co.finanzas.application.ingestion.port.out;

import co.finanzas.application.ingestion.model.SourceConnection;
import java.util.Optional;
import java.util.UUID;

public interface SourceConnectionRepository {
    Optional<SourceConnection> find(UUID connectionId);
}
