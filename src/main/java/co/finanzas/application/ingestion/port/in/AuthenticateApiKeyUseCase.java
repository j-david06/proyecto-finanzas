package co.finanzas.application.ingestion.port.in;

import co.finanzas.application.ingestion.model.AuthenticatedSource;

public interface AuthenticateApiKeyUseCase {
    AuthenticatedSource authenticate(String apiKey);
}
