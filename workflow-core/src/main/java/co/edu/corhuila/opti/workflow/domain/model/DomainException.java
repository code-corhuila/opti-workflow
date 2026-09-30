package co.edu.corhuila.opti.workflow.domain.model;

import java.util.List;

/**
 * Typed domain error. The domain knows the kind of failure, never the HTTP status: the
 * inbound adapter translates {@link ErrorKind} into a status code in a single place.
 */
public class DomainException extends RuntimeException {

    private final ErrorKind kind;
    private final List<FieldError> fields;

    public DomainException(ErrorKind kind, String message) {
        this(kind, message, List.of());
    }

    public DomainException(ErrorKind kind, String message, List<FieldError> fields) {
        super(message);
        this.kind = kind;
        this.fields = List.copyOf(fields);
    }

    public static DomainException validation(String field, String message) {
        return new DomainException(ErrorKind.VALIDATION, message, List.of(new FieldError(field, message)));
    }

    public static DomainException validation(List<FieldError> fields) {
        return new DomainException(ErrorKind.VALIDATION, "the request has invalid fields", fields);
    }

    public static DomainException unauthenticated(String message) {
        return new DomainException(ErrorKind.UNAUTHENTICATED, message);
    }

    public static DomainException notFound(String message) {
        return new DomainException(ErrorKind.NOT_FOUND, message);
    }

    public static DomainException rule(String message) {
        return new DomainException(ErrorKind.BUSINESS_RULE_VIOLATION, message);
    }

    public static DomainException transition(String message) {
        return new DomainException(ErrorKind.INVALID_STATUS_TRANSITION, message);
    }

    public ErrorKind kind() {
        return kind;
    }

    /** Every invalid field, empty when the error is not about input fields. */
    public List<FieldError> fields() {
        return fields;
    }
}
