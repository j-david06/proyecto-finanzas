package co.finanzas.application.ingestion.service;

import co.finanzas.application.ingestion.model.AuthenticatedSource;
import co.finanzas.application.ingestion.model.IngestionProblem;
import co.finanzas.application.ingestion.model.IngestionReceipt;
import co.finanzas.application.ingestion.port.in.ReceiveIngestionUseCase;
import co.finanzas.application.ingestion.port.out.DigestPort;
import co.finanzas.application.ingestion.port.out.IngestionRepository;
import co.finanzas.application.ingestion.port.out.PayloadCipherPort;
import co.finanzas.domain.shared.Money;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ReceiveIngestionService implements ReceiveIngestionUseCase {
    private final IngestionRepository repository;
    private final DigestPort digests;
    private final PayloadCipherPort cipher;

    public ReceiveIngestionService(IngestionRepository repository, DigestPort digests, PayloadCipherPort cipher) {
        this.repository = repository;
        this.digests = digests;
        this.cipher = cipher;
    }

    @Override
    public IngestionReceipt receive(AuthenticatedSource source, UUID eventId, UUID accountId,
                                    Money amount, byte[] rawBody) {
        if (!source.allowedAccounts().contains(accountId)) {
            throw new IngestionProblem(IngestionProblem.Code.FORBIDDEN);
        }
        byte[] aad = (source.ownerId().value() + ":" + source.connectionId() + ":" + eventId)
                .getBytes(StandardCharsets.UTF_8);
        return repository.accept(source, eventId, accountId, amount,
                digests.sha256(rawBody), cipher.encrypt(rawBody, aad));
    }

    @Override
    public IngestionReceipt status(AuthenticatedSource source, UUID ingestionId) {
        return repository.find(source, ingestionId)
                .orElseThrow(() -> new IngestionProblem(IngestionProblem.Code.NOT_FOUND));
    }
}
