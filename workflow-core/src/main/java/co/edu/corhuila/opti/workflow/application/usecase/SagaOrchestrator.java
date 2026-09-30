package co.edu.corhuila.opti.workflow.application.usecase;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.opti.workflow.application.port.out.SagaStore;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.opti.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.opti.workflow.domain.saga.SagaType;

/**
 * Runs a saga step by step and persists the state after every step. The same method starts a
 * saga and resumes it after a restart: steps already done are skipped, and every participant call
 * is idempotent, so repeating one is harmless. When a step is refused the completed ones are
 * undone in reverse order.
 */
class SagaOrchestrator {

    private static final System.Logger LOG = System.getLogger(SagaOrchestrator.class.getName());

    private final Map<SagaType, List<Step>> definitions;
    private final SagaStore store;
    private final Clock clock;
    private final int maxAttempts;

    SagaOrchestrator(Map<SagaType, List<Step>> definitions, SagaStore store, Clock clock, int maxAttempts) {
        this.definitions = definitions;
        this.store = store;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
    }

    SagaInstance run(SagaInstance saga) {
        if (saga.status().isFinal()) {
            return saga;
        }
        if (saga.status() == SagaStatus.COMPENSATING) {
            return compensate(saga);
        }
        SagaInstance current = saga;
        for (Step step : definitions.get(saga.type())) {
            if (current.isDone(step.name())) {
                continue;
            }
            try {
                Map<String, String> produced = step.execute(current);
                current = current.stepDone(step.name(), produced, clock.instant());
                store.save(current);
            } catch (ParticipantFailure failure) {
                return handleFailure(current, step, failure);
            }
        }
        current = current.complete(clock.instant());
        store.save(current);
        return current;
    }

    private SagaInstance handleFailure(SagaInstance saga, Step step, ParticipantFailure failure) {
        LOG.log(System.Logger.Level.WARNING, "saga {0} step {1} failed ({2}): {3}", saga.id(), step.name(),
                failure.kind(), failure.detail());
        if (failure.kind() == ParticipantFailure.Kind.TECHNICAL) {
            SagaInstance waiting = saga.technicalFailure(step.name(), failure.detail(), maxAttempts, clock.instant());
            store.save(waiting);
            return waiting;
        }
        if (!canUndoWhatIsDone(saga)) {
            SagaInstance stuck = saga.businessFailure(step.name(), failure.reason(), failure.detail(), clock.instant())
                    .compensationFailed("step " + step.name() + " was refused after a step that cannot be undone: "
                            + failure.detail(), clock.instant());
            store.save(stuck);
            return stuck;
        }
        SagaInstance undoing = saga.businessFailure(step.name(), failure.reason(), failure.detail(), clock.instant());
        store.save(undoing);
        return compensate(undoing);
    }

    private boolean canUndoWhatIsDone(SagaInstance saga) {
        return definitions.get(saga.type()).stream()
                .filter(step -> saga.isDone(step.name()))
                .allMatch(Step::compensable);
    }

    /** Undoes, in reverse order, the steps that are done and not yet undone. */
    private SagaInstance compensate(SagaInstance saga) {
        SagaInstance current = saga;
        List<Step> steps = definitions.get(saga.type());
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            if (!current.isDone(step.name()) || current.isCompensated(step.name())) {
                continue;
            }
            try {
                step.compensate(current);
                current = current.stepCompensated(step.name(), clock.instant());
                store.save(current);
            } catch (ParticipantFailure failure) {
                LOG.log(System.Logger.Level.ERROR, "saga {0}: compensation of {1} failed: {2}", saga.id(),
                        step.name(), failure.detail());
                SagaInstance failed = current.compensationFailed("undo of " + step.name() + ": " + failure.detail(),
                        clock.instant());
                store.save(failed);
                return failed;
            }
        }
        SagaInstance done = current.compensated(clock.instant());
        store.save(done);
        return done;
    }
}
