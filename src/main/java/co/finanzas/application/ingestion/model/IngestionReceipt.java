package co.finanzas.application.ingestion.model;

import java.util.UUID;

public record IngestionReceipt(UUID ingestionId, String status) {
    public boolean terminal() {
        return status.equals("PROCESSED") || status.equals("REVIEW") || status.equals("DEAD");
    }
}
