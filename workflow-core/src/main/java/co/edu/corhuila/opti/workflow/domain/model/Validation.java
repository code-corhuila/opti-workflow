package co.edu.corhuila.opti.workflow.domain.model;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Reusable field rules. Every failure names the field so the client can show it next to the input. */
public final class Validation {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private Validation() {
    }

    public static String text(String value, String field, int min, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            throw DomainException.validation(field, min == 0
                    ? "must have at most " + max + " characters"
                    : "must have between " + min + " and " + max + " characters");
        }
        return trimmed;
    }

    /** The {@code Idempotency-Key} header of a creation: 8 to 128 characters. */
    public static String idempotencyKey(String value) {
        if (value == null || value.length() < 8 || value.length() > 128) {
            throw validation("Idempotency-Key", "header required, 8 to 128 characters");
        }
        return value;
    }

    private static DomainException validation(String field, String message) {
        return DomainException.validation(field, message);
    }

    public static String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return text(value, field, 1, max);
    }

    public static String matching(String value, String field, Pattern pattern, String hint) {
        String trimmed = value == null ? "" : value.trim();
        if (!pattern.matcher(trimmed).matches()) {
            throw DomainException.validation(field, hint);
        }
        return trimmed;
    }

    public static String optionalEmail(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 160 || !EMAIL.matcher(trimmed).matches()) {
            throw DomainException.validation(field, "must be a valid email address");
        }
        return trimmed.toLowerCase();
    }

    public static long positive(long value, String field) {
        if (value <= 0) {
            throw DomainException.validation(field, "must be greater than zero");
        }
        return value;
    }

    public static long nonNegative(long value, String field) {
        if (value < 0) {
            throw DomainException.validation(field, "must not be negative");
        }
        return value;
    }

    public static int intBetween(int value, String field, int min, int max) {
        if (value < min || value > max) {
            throw DomainException.validation(field, "must be between " + min + " and " + max);
        }
        return value;
    }

    public static LocalDate pastOrToday(LocalDate value, String field, LocalDate today) {
        if (value != null && value.isAfter(today)) {
            throw DomainException.validation(field, "must not be in the future");
        }
        return value;
    }

    public static <T> T required(T value, String field) {
        if (value == null) {
            throw DomainException.validation(field, "is required");
        }
        return value;
    }
}
