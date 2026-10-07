package co.edu.corhuila.opti.workflow.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Collects every failed field rule so the caller gets all problems at once, not one per attempt.
 * <pre>
 *   Violations v = new Violations();
 *   String name = v.check(() -> Validation.text(raw, "name", 2, 100));
 *   v.throwIfAny();
 * </pre>
 */
public final class Violations {

    private final List<FieldError> found = new ArrayList<>();

    /** Runs a rule; a validation failure is recorded and {@code null} is returned. */
    public <T> T check(Supplier<T> rule) {
        try {
            return rule.get();
        } catch (DomainException e) {
            if (e.kind() != ErrorKind.VALIDATION) {
                throw e;
            }
            found.addAll(e.fields());
            return null;
        }
    }

    public void throwIfAny() {
        if (!found.isEmpty()) {
            throw DomainException.validation(found);
        }
    }
}
