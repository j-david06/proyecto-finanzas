package co.finanzas.adapters.out.parser;

import co.finanzas.adapters.shared.ingestion.IngestionRequestDecoder;
import co.finanzas.adapters.shared.ingestion.PayloadDecodingException;
import co.finanzas.application.processing.model.*;
import co.finanzas.application.processing.port.out.MovementParserPort;
import co.finanzas.domain.transaction.TransactionKind;
import java.time.OffsetDateTime;
import static co.finanzas.application.processing.model.ProcessingFailure.Code.*;

public final class NormalizedMovementParser implements MovementParserPort {
    private final IngestionRequestDecoder decoder;
    public NormalizedMovementParser(IngestionRequestDecoder decoder) { this.decoder = decoder; }
    @Override public ParsedMovement parse(byte[] plaintext) {
        try {
            var decoded = decoder.decode(plaintext);
            if (!decoded.request().format().equals("NORMALIZED")) throw new ProcessingFailure(PARSER_UNAVAILABLE, false);
            var movement = decoded.request().movement();
            return new ParsedMovement(decoded.request().accountId(), TransactionKind.valueOf(movement.kind()),
                    decoded.amount(), OffsetDateTime.parse(movement.occurredAt()).toInstant(), movement.merchant(),
                    movement.bankProvider(), movement.bankEventId(), "normalized-v1");
        } catch (PayloadDecodingException | IllegalArgumentException exception) {
            throw new ProcessingFailure(INVALID_PAYLOAD, false);
        }
    }
}
