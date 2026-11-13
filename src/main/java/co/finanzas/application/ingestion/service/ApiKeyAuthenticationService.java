package co.finanzas.application.ingestion.service;

import co.finanzas.application.ingestion.model.AuthenticatedSource;
import co.finanzas.application.ingestion.model.IngestionProblem;
import co.finanzas.application.ingestion.port.in.AuthenticateApiKeyUseCase;
import co.finanzas.application.ingestion.port.out.DigestPort;
import co.finanzas.application.ingestion.port.out.SourceConnectionRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

import static co.finanzas.application.ingestion.model.IngestionProblem.Code.UNAUTHORIZED;

public final class ApiKeyAuthenticationService implements AuthenticateApiKeyUseCase {
    private static final Pattern KEY = Pattern.compile(
            "^pf_ing_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.[A-Za-z0-9_-]{43,128}$");
    private final SourceConnectionRepository connections;
    private final DigestPort digests;
    private final Clock clock;

    public ApiKeyAuthenticationService(SourceConnectionRepository connections, DigestPort digests, Clock clock) {
        this.connections = connections;
        this.digests = digests;
        this.clock = clock;
    }

    @Override
    public AuthenticatedSource authenticate(String apiKey) {
        if (apiKey == null) throw new IngestionProblem(UNAUTHORIZED);
        var matcher = KEY.matcher(apiKey);
        if (!matcher.matches()) throw new IngestionProblem(UNAUTHORIZED);
        byte[] hash = digests.sha256(apiKey.getBytes(StandardCharsets.UTF_8));
        var connection = connections.find(UUID.fromString(matcher.group(1)));
        // Always compare a fixed-size digest, including for unknown connections.
        boolean matches = digests.matches(connection.map(c -> c.apiKeyHash()).orElseGet(() -> new byte[32]), hash);
        if (connection.isEmpty() || !matches) throw new IngestionProblem(UNAUTHORIZED);
        var c = connection.orElseThrow();
        if (!c.active() || !c.source().equals("IOS_SHORTCUTS")
                || !c.authenticationProfile().equals("API_KEY_ONLY")
                || (c.expiresAt() != null && !c.expiresAt().isAfter(clock.instant()))) {
            throw new IngestionProblem(UNAUTHORIZED);
        }
        return new AuthenticatedSource(c.id(), c.ownerId(), hash, c.allowedAccounts());
    }
}
