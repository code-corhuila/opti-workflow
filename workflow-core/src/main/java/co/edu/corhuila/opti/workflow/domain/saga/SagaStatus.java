package co.edu.corhuila.opti.workflow.domain.saga;

/** Where a saga is. COMPLETED, COMPENSATED and FAILED are final. */
public enum SagaStatus {
    /** Executing, or waiting to be resumed after a temporary failure of a participant. */
    RUNNING,
    /** A step failed: the completed steps are being undone, in reverse order. */
    COMPENSATING,
    COMPLETED,
    /** A step failed and everything before it was undone. The system is consistent again. */
    COMPENSATED,
    /** Something could not be undone or repeated: an operator must decide. It is never hidden. */
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == COMPENSATED || this == FAILED;
    }
}
