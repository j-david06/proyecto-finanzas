package co.finanzas.ingestion;

import co.finanzas.adapters.shared.ingestion.PayloadDecodingException;
import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IngestionDecoderTest {
    private static final String VALID = """
            {"schema_version":1,"trigger":"APPLE_PAY","format":"NORMALIZED",
             "account_id":"%s","movement":{"kind":"EXPENSE","amount":"45900.00",
             "occurred_at":"2026-11-13T13:45:12-05:00","merchant":"Comercio"}}
            """.formatted(UUID.randomUUID());

    @Test void parsesCopAndRejectsCoercionInvalidDecimalsAndMissingOffset() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var decoder = new IngestionRequestDecoder(new ObjectMapper(), factory.getValidator());
            assertEquals(new BigDecimal("45900.00"), decoder.decode(bytes(VALID)).amount().amount());
            for (String invalid : new String[]{VALID.replace("\"45900.00\"", "45900.00"),
                    VALID.replace("45900.00", "0.001"), VALID.replace("45900.00", "0"),
                    VALID.replace("1,\"trigger\"", "1.9,\"trigger\""),
                    VALID.replace("2026-11-13T13:45:12-05:00", "2026-11-13T13:45:12"),
                    VALID.replace("EXPENSE", "TRANSFER"), VALID.replace("Comercio", "   ")}) {
                assertThrows(PayloadDecodingException.class, () -> decoder.decode(bytes(invalid)), invalid);
            }
        }
    }

    @Test void rejectsDuplicatesUnknownFieldsAndTrailingDocuments() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var decoder = new IngestionRequestDecoder(new ObjectMapper(), factory.getValidator());
            for (String invalid : new String[]{VALID.replace("\"schema_version\":1", "\"schema_version\":1,\"schema_version\":1"),
                    VALID.replace("\"schema_version\":1", "\"schema_version\":1,\"owner_id\":\"forged\""), VALID + "{}"}) {
                assertEquals(400, assertThrows(PayloadDecodingException.class, () -> decoder.decode(bytes(invalid))).status());
            }
        }
    }

    @Test void rawMessagesRequireCommunicationTriggerAndExclusiveMessageShape() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var decoder = new IngestionRequestDecoder(new ObjectMapper(), factory.getValidator());
            String raw = """
                    {"schema_version":1,"trigger":"SMS","format":"RAW_MESSAGE","account_id":"%s",
                    "message":{"text":"Mensaje transaccional","received_at":"2026-11-13T13:46:00-05:00"}}
                    """.formatted(UUID.randomUUID());
            assertNull(decoder.decode(bytes(raw)).amount());
            assertThrows(PayloadDecodingException.class, () -> decoder.decode(bytes(raw.replace("SMS", "APPLE_PAY"))));
            assertThrows(PayloadDecodingException.class, () -> decoder.decode(bytes(raw.replace("RAW_MESSAGE", "NORMALIZED"))));
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
