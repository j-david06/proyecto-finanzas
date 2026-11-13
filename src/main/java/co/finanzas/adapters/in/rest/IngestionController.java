package co.finanzas.adapters.in.rest;

import co.finanzas.application.ingestion.model.AuthenticatedSource;
import co.finanzas.application.ingestion.port.in.ReceiveIngestionUseCase;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@Profile("api & !worker")
public class IngestionController {
    public record ReceiptBody(@com.fasterxml.jackson.annotation.JsonProperty("ingestion_id") UUID ingestionId, String status) { }
    private final ReceiveIngestionUseCase ingestion;
    private final IngestionRequestDecoder decoder;
    public IngestionController(ReceiveIngestionUseCase ingestion, IngestionRequestDecoder decoder) {
        this.ingestion = ingestion;
        this.decoder = decoder;
    }

    @PostMapping(value = "/api/v1/transactions/ingest", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ReceiptBody> ingest(@AuthenticationPrincipal AuthenticatedSource source,
                                              @RequestHeader("Idempotency-Key") String key, @RequestBody byte[] body) {
        UUID event = canonicalUuid(key);
        var decoded = decoder.decode(body);
        var receipt = ingestion.receive(source, event, decoded.request().accountId(), decoded.amount(), body);
        return ResponseEntity.status(receipt.terminal() ? 200 : 202)
                .location(URI.create("/api/v1/ingestions/" + receipt.ingestionId()))
                .header("Cache-Control", "no-store")
                .body(new ReceiptBody(receipt.ingestionId(), receipt.status()));
    }

    @GetMapping(value = "/api/v1/ingestions/{id}", produces = "application/json")
    public ResponseEntity<ReceiptBody> status(@AuthenticationPrincipal AuthenticatedSource source, @PathVariable String id) {
        var receipt = ingestion.status(source, canonicalUuid(id));
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new ReceiptBody(receipt.ingestionId(), receipt.status()));
    }

    private static UUID canonicalUuid(String value) {
        try {
            var uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) throw new IllegalArgumentException();
            return uuid;
        } catch (IllegalArgumentException exception) { throw new ApiProblem(400, "INVALID_IDENTIFIER"); }
    }
}
