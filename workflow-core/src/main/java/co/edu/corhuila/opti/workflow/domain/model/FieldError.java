package co.edu.corhuila.opti.workflow.domain.model;

/** One invalid input field and the reason. */
public record FieldError(String field, String message) {
}
