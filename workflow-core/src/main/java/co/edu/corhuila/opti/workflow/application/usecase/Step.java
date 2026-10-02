package co.edu.corhuila.opti.workflow.application.usecase;

import java.util.Map;

import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;

/**
 * One step of a saga together with the action that undoes it. Both must be idempotent: after a
 * crash, or a failure during the undo itself, they can run again.
 */
interface Step {

    String name();

    /** Does the work and returns what the next steps need (ids, prices). May throw ParticipantFailure. */
    Map<String, String> execute(SagaInstance saga);

    /** Undoes the step. Only called for steps that completed. Nothing to undo by default. */
    default void compensate(SagaInstance saga) {
        // nothing to undo
    }

    /**
     * False for a step that cannot be undone: once it is done, a later failure cannot be compensated,
     * so the saga ends FAILED and an operator decides.
     */
    default boolean compensable() {
        return true;
    }
}
