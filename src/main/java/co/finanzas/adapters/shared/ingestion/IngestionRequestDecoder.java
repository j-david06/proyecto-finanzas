package co.finanzas.adapters.shared.ingestion;

import co.finanzas.domain.shared.Money;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Currency;

public final class IngestionRequestDecoder {
    public record Decoded(IngestionRequest request, Money amount) { }
    private final ObjectMapper mapper;
    private final Validator validator;

    public IngestionRequestDecoder(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.validator = validator;
    }

    public Decoded decode(byte[] body) {
        try {
            JsonNode tree = mapper.readTree(body);
            if (tree == null || !tree.isObject()) throw new PayloadDecodingException(400, "INVALID_JSON");
            if (!tree.path("schema_version").isIntegralNumber()) invalid();
            requireText(tree, "trigger", "format", "account_id");
            if (tree.path("movement").isObject()) {
                requireText(tree.path("movement"), "kind", "amount", "occurred_at");
                requireOptionalText(tree.path("movement"), "currency", "merchant", "bank_provider", "bank_event_id");
            }
            if (tree.path("message").isObject()) requireText(tree.path("message"), "text", "received_at");
            IngestionRequest request = mapper.treeToValue(tree, IngestionRequest.class);
            if (!validator.validate(request).isEmpty()) invalid();
            if (request.format().equals("NORMALIZED")) {
                if (request.movement() == null || request.message() != null) invalid();
                var movement = request.movement();
                OffsetDateTime.parse(movement.occurredAt());
                if (movement.bankEventId() != null && (movement.bankProvider() == null || movement.bankProvider().isBlank())) invalid();
                for (String text : new String[]{movement.merchant(), movement.bankProvider(), movement.bankEventId()}) {
                    if (text != null && text.isBlank()) invalid();
                }
                Money amount = new Money(new BigDecimal(movement.amount()), movement.currency() == null
                        ? Money.COP : Currency.getInstance(movement.currency()));
                return new Decoded(request, amount);
            }
            if (request.message() == null || request.movement() != null || request.trigger().equals("APPLE_PAY")) invalid();
            OffsetDateTime.parse(request.message().receivedAt());
            return new Decoded(request, null);
        } catch (JsonProcessingException exception) {
            throw new PayloadDecodingException(400, "INVALID_JSON");
        } catch (DateTimeParseException exception) {
            throw new PayloadDecodingException(422, "INVALID_MOVEMENT");
        } catch (IllegalArgumentException exception) {
            throw new PayloadDecodingException(422, "INVALID_MOVEMENT");
        } catch (IOException exception) {
            throw new PayloadDecodingException(400, "INVALID_JSON");
        }
    }

    private static void requireText(JsonNode node, String... fields) {
        for (String field : fields) if (!node.path(field).isTextual()) invalid();
    }
    private static void requireOptionalText(JsonNode node, String... fields) {
        for (String field : fields) if (node.hasNonNull(field) && !node.path(field).isTextual()) invalid();
    }
    private static void invalid() { throw new PayloadDecodingException(422, "INVALID_MOVEMENT"); }
}
