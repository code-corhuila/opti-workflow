package co.edu.corhuila.opti.workflow.domain.model;

/** Kinds of domain failure. Mapped to the common error codes by the HTTP adapter. */
public enum ErrorKind {
    VALIDATION,
    /** Credentials that do not identify anyone (login). Mapped to 401. */
    UNAUTHENTICATED,
    NOT_FOUND,
    INVALID_STATUS_TRANSITION,
    BUSINESS_RULE_VIOLATION
}
