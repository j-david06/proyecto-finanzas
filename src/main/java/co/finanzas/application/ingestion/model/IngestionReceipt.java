package co.finanzas.application.ingestion.model;

import java.util.UUID;

public record IngestionReceipt(UUID ingestionId, String status, UUID transactionId, String errorCode, int attempts) {
    public IngestionReceipt(UUID ingestionId, String status) { this(ingestionId, status, null, null, 0); }
    public boolean terminal() {
        return status.equals("PROCESSED") || status.equals("REVIEW") || status.equals("DEAD");
    }
}
