package co.finanzas.domain.shared;

import java.util.Objects;

public final class DomainChecks {
    private DomainChecks() { }

    public static String requiredText(String value, String field, int maxLength) {
        String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1.." + maxLength + " characters");
        }
        return normalized;
    }

    public static String optionalText(String value, String field, int maxLength) {
        return value == null ? null : requiredText(value, field, maxLength);
    }

    public static void sameOwner(OwnerId expected, OwnerId actual) {
        if (!Objects.requireNonNull(expected, "expectedOwner").equals(actual)) {
            throw new IllegalArgumentException("Resources must belong to the same owner");
        }
    }
}
