package co.finanzas.domain.shared;

import java.util.Objects;
import java.util.UUID;

public record OwnerId(UUID value) {
    public OwnerId {
        Objects.requireNonNull(value, "ownerId");
    }
}
