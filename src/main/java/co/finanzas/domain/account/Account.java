package co.finanzas.domain.account;

import co.finanzas.domain.shared.DomainChecks;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.OwnerId;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record Account(UUID id, OwnerId ownerId, String name, Kind kind,
                      Currency currency, boolean active) {
    public enum Kind { CASH, DEPOSIT, CREDIT_CARD }

    public Account {
        Objects.requireNonNull(id, "accountId");
        Objects.requireNonNull(ownerId, "ownerId");
        name = DomainChecks.requiredText(name, "name", 120);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(currency, "currency");
    }

    public Account(UUID id, OwnerId ownerId, String name, Kind kind) {
        this(id, ownerId, name, kind, Money.COP, true);
    }

    public void requireCompatible(OwnerId owner, Money money) {
        DomainChecks.sameOwner(owner, ownerId);
        if (!currency.equals(Objects.requireNonNull(money, "money").currency())) {
            throw new IllegalArgumentException("Account and movement currencies differ");
        }
    }

    public void requireActive() {
        if (!active) {
            throw new IllegalStateException("Account is inactive");
        }
    }

    public Account deactivate() {
        return new Account(id, ownerId, name, kind, currency, false);
    }
}
