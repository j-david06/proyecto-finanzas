package co.finanzas.domain.category;

import co.finanzas.domain.shared.DomainChecks;
import co.finanzas.domain.shared.OwnerId;
import co.finanzas.domain.transaction.TransactionKind;

import java.util.Objects;
import java.util.UUID;

public record Category(UUID id, OwnerId ownerId, String name, TransactionKind kind, boolean active) {
    public Category {
        Objects.requireNonNull(id, "categoryId");
        Objects.requireNonNull(ownerId, "ownerId");
        name = DomainChecks.requiredText(name, "name", 80);
        Objects.requireNonNull(kind, "kind");
        if (kind == TransactionKind.TRANSFER) {
            throw new IllegalArgumentException("Transfers do not use income/expense categories");
        }
    }

    public void requireCompatible(OwnerId owner, TransactionKind movementKind) {
        DomainChecks.sameOwner(owner, ownerId);
        if (kind != movementKind) {
            throw new IllegalArgumentException("Category and movement kinds differ");
        }
    }

    public void requireActive() {
        if (!active) {
            throw new IllegalStateException("Category is inactive");
        }
    }
}
