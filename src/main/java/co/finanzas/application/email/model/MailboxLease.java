package co.finanzas.application.email.model;

import co.finanzas.domain.shared.OwnerId;
import java.util.UUID;

public record MailboxLease(UUID connectionId,OwnerId ownerId,UUID accountId,UUID destinationAccountId,
                           String folder,Long uidValidity,long lastUid,UUID token) { }
