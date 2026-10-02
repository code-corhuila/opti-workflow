package co.edu.corhuila.opti.workflow.domain.saga;

/**
 * Why a saga did not complete, from a closed list that is safe to show: it never carries server
 * names, ports or status codes. The detail stays in the saga state and in the log.
 */
public enum FailureReason {
    PATIENT_NOT_FOUND,
    PATIENT_NOT_ACTIVE,
    FRAME_NOT_FOUND,
    INSUFFICIENT_STOCK,
    ORDER_NOT_FOUND,
    ORDER_NOT_CANCELLABLE,
    /** The participant refused the request for a rule that has no more specific reason. */
    REJECTED,
    /** A participant did not answer, even after the allowed retries. */
    PARTICIPANT_UNAVAILABLE,
    /** A compensation failed: an operator has to finish the undo by hand. */
    COMPENSATION_FAILED
}
