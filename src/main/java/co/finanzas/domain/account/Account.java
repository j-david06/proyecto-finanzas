package co.finanzas.domain.account;

import co.finanzas.domain.shared.DomainChecks;
import co.finanzas.domain.shared.Money;
import co.finanzas.domain.shared.OwnerId;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record Account(UUID id, OwnerId ownerId, String name, Kind kind,
                      Currency currency, boolean active, String bankReference, String cardLast4) {
    public enum Kind { CASH, DEPOSIT, CREDIT_CARD }

    public Account {
        Objects.requireNonNull(id, "accountId");
        Objects.requireNonNull(ownerId, "ownerId");
        name = DomainChecks.requiredText(name, "name", 120);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(currency, "currency");
        bankReference = DomainChecks.optionalText(bankReference, "bankReference", 120);
        if (cardLast4 != null && !cardLast4.matches("[0-9]{4}")) throw new IllegalArgumentException("Invalid card last four digits");
        if (cardLast4 != null && kind != Kind.CREDIT_CARD) throw new IllegalArgumentException("Card mapping requires a credit card account");
    }

    public Account(UUID id, OwnerId ownerId, String name, Kind kind, Currency currency, boolean active) {
        this(id, ownerId, name, kind, currency, active, null, null);
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
        return new Account(id, ownerId, name, kind, currency, false, bankReference, cardLast4);
    }
}
