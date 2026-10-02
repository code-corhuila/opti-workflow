package co.edu.corhuila.opti.workflow.application.port.out;

import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;

/**
 * A participant did not do what the saga asked. BUSINESS is a definite refusal (the answer will
 * not change if repeated); TECHNICAL is a failure to get an answer (retrying may work).
 */
public class ParticipantFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Whether the failure is definite. */
    public enum Kind {
        BUSINESS,
        TECHNICAL
    }

    private final Kind kind;
    private final FailureReason reason;

    private ParticipantFailure(Kind kind, FailureReason reason, String detail) {
        super(detail);
        this.kind = kind;
        this.reason = reason;
    }

    public static ParticipantFailure business(FailureReason reason, String detail) {
        return new ParticipantFailure(Kind.BUSINESS, reason, detail);
    }

    public static ParticipantFailure technical(String detail) {
        return new ParticipantFailure(Kind.TECHNICAL, FailureReason.PARTICIPANT_UNAVAILABLE, detail);
    }

    public Kind kind() {
        return kind;
    }

    public FailureReason reason() {
        return reason;
    }

    /** Internal detail: for the saga state and the log, never for a response. */
    public String detail() {
        return getMessage();
    }
}
