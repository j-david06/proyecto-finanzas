package co.finanzas.adapters.shared.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record IngestionRequest(
        @JsonProperty("schema_version") @NotNull @Min(1) @Max(1) Integer schemaVersion,
        @NotNull @Pattern(regexp = "APPLE_PAY|SMS|EMAIL") String trigger,
        @NotNull @Pattern(regexp = "NORMALIZED|RAW_MESSAGE") String format,
        @JsonProperty("account_id") @NotNull UUID accountId,
        @Valid Movement movement, @Valid Message message) {

    public record Movement(
            @NotNull @Pattern(regexp = "INCOME|EXPENSE") String kind,
            @NotNull @Pattern(regexp = "(?:0|[1-9][0-9]{0,15})(?:\\.[0-9]{1,2})?") String amount,
            @Pattern(regexp = "[A-Z]{3}") String currency,
            @JsonProperty("occurred_at") @NotBlank @Size(max = 40) String occurredAt,
            @Size(min = 1, max = 200) String merchant,
            @JsonProperty("bank_provider") @Size(min = 1, max = 40) String bankProvider,
            @JsonProperty("bank_event_id") @Size(min = 1, max = 200) String bankEventId) { }

    public record Message(@NotBlank @Size(max = 32768) String text,
                          @JsonProperty("received_at") @NotBlank @Size(max = 40) String receivedAt) { }
}
