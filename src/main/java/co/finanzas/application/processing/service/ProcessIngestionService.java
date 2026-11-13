package co.finanzas.application.processing.service;

import co.finanzas.application.ingestion.port.out.DigestPort;
import co.finanzas.application.processing.model.*;
import co.finanzas.application.processing.port.in.ProcessIngestionUseCase;
import co.finanzas.application.processing.port.out.*;
import co.finanzas.domain.transaction.Transaction;
import co.finanzas.domain.transaction.TransactionStatus;
import java.util.Arrays;
import static co.finanzas.application.processing.model.ProcessingFailure.Code.*;

public final class ProcessIngestionService implements ProcessIngestionUseCase {
    private final ProcessingRepository repository;
    private final PayloadDecryptionPort cipher;
    private final MovementParserPort parser;
    private final DigestPort digests;

    public ProcessIngestionService(ProcessingRepository repository, PayloadDecryptionPort cipher,
                                   MovementParserPort parser, DigestPort digests) {
        this.repository = repository; this.cipher = cipher; this.parser = parser; this.digests = digests;
    }

    @Override public boolean processNext() {
        var claimed = repository.claim();
        if (claimed.isEmpty()) return false;
        var work = claimed.orElseThrow();
        byte[] plaintext = null;
        try {
            plaintext = cipher.decrypt(work.payload(), work.associatedData());
            if (!digests.matches(work.hash(), digests.sha256(plaintext))) {
                throw new ProcessingFailure(PAYLOAD_HASH_MISMATCH, false);
            }
            var movement = parser.parse(plaintext);
            var account = repository.account(work, movement.accountId());
            var transaction = new Transaction(work.id(), work.owner(), account, null, null, null,
                    movement.kind(), TransactionStatus.POSTED, movement.amount(), movement.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                    movement.merchant(), null, movement.bankProvider(), movement.bankEventId(), null, null);
            transaction.requireRegistrable();
            repository.complete(work, transaction, movement.parserVersion());
        } catch (ProcessingFailure failure) {
            repository.fail(work, failure);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            repository.fail(work, new ProcessingFailure(INVALID_PAYLOAD, false));
        } catch (RuntimeException failure) {
            repository.fail(work, new ProcessingFailure(TEMPORARY_FAILURE, true));
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
        return true;
    }
}
