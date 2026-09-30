package co.edu.corhuila.opti.workflow.domain.saga;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One execution of a saga: what it was asked to do, which steps are done, which were undone and
 * why it stopped. Immutable: every change returns a new instance, so what is saved after each
 * step is exactly what a restarted workflow reads back.
 */
public final class SagaInstance {

    private final UUID id;
    private final SagaType type;
    private final SagaStatus status;
    private final Map<String, String> data;
    private final List<String> completedSteps;
    private final List<String> compensatedSteps;
    private final String failedStep;
    private final FailureReason failureReason;
    private final String detail;
    private final int attempts;
    private final Instant createdAt;
    private final Instant updatedAt;

    private SagaInstance(UUID id, SagaType type, SagaStatus status, Map<String, String> data,
                         List<String> completedSteps, List<String> compensatedSteps, String failedStep,
                         FailureReason failureReason, String detail, int attempts, Instant createdAt,
                         Instant updatedAt) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.data = Map.copyOf(data);
        this.completedSteps = List.copyOf(completedSteps);
        this.compensatedSteps = List.copyOf(compensatedSteps);
        this.failedStep = failedStep;
        this.failureReason = failureReason;
        this.detail = detail;
        this.attempts = attempts;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static SagaInstance start(UUID id, SagaType type, Map<String, String> input, Instant now) {
        return new SagaInstance(id, type, SagaStatus.RUNNING, input, List.of(), List.of(), null, null, null, 0, now, now);
    }

    public static SagaInstance rehydrate(UUID id, SagaType type, SagaStatus status, Map<String, String> data,
                                         List<String> completedSteps, List<String> compensatedSteps,
                                         String failedStep, FailureReason failureReason, String detail, int attempts,
                                         Instant createdAt, Instant updatedAt) {
        return new SagaInstance(id, type, status, data, completedSteps, compensatedSteps, failedStep, failureReason,
                detail, attempts, createdAt, updatedAt);
    }

    /** A step finished: remember it and any value it produced for the next steps. */
    public SagaInstance stepDone(String step, Map<String, String> produced, Instant now) {
        Map<String, String> merged = new LinkedHashMap<>(data);
        merged.putAll(produced);
        List<String> done = new ArrayList<>(completedSteps);
        done.add(step);
        return new SagaInstance(id, type, status, merged, done, compensatedSteps, failedStep, failureReason, detail, 0,
                createdAt, now);
    }

    /** A participant refused for a business reason: this is definite, so the undo starts. */
    public SagaInstance businessFailure(String step, FailureReason reason, String internalDetail, Instant now) {
        return new SagaInstance(id, type, SagaStatus.COMPENSATING, data, completedSteps, compensatedSteps, step, reason,
                internalDetail, attempts, createdAt, now);
    }

    /**
     * A participant did not answer. The saga stays RUNNING to be resumed later (every step is
     * idempotent) until the attempts are used up: then an operator has to look.
     */
    public SagaInstance technicalFailure(String step, String internalDetail, int maxAttempts, Instant now) {
        int used = attempts + 1;
        if (used >= maxAttempts) {
            return new SagaInstance(id, type, SagaStatus.FAILED, data, completedSteps, compensatedSteps, step,
                    FailureReason.PARTICIPANT_UNAVAILABLE, internalDetail, used, createdAt, now);
        }
        return new SagaInstance(id, type, SagaStatus.RUNNING, data, completedSteps, compensatedSteps, failedStep,
                failureReason, internalDetail, used, createdAt, now);
    }

    public SagaInstance stepCompensated(String step, Instant now) {
        List<String> undone = new ArrayList<>(compensatedSteps);
        undone.add(step);
        return new SagaInstance(id, type, status, data, completedSteps, undone, failedStep, failureReason, detail,
                attempts, createdAt, now);
    }

    public SagaInstance compensated(Instant now) {
        return new SagaInstance(id, type, SagaStatus.COMPENSATED, data, completedSteps, compensatedSteps, failedStep,
                failureReason, detail, attempts, createdAt, now);
    }

    /** An undo failed: stop and expose it. Ending in silence would leave a half-done process nobody knows about. */
    public SagaInstance compensationFailed(String internalDetail, Instant now) {
        return new SagaInstance(id, type, SagaStatus.FAILED, data, completedSteps, compensatedSteps, failedStep,
                FailureReason.COMPENSATION_FAILED, internalDetail, attempts, createdAt, now);
    }

    public SagaInstance complete(Instant now) {
        return new SagaInstance(id, type, SagaStatus.COMPLETED, data, completedSteps, compensatedSteps, null, null,
                null, 0, createdAt, now);
    }

    public boolean isDone(String step) {
        return completedSteps.contains(step);
    }

    public boolean isCompensated(String step) {
        return compensatedSteps.contains(step);
    }

    public String datum(String key) {
        return data.get(key);
    }

    public UUID id() {
        return id;
    }

    public SagaType type() {
        return type;
    }

    public SagaStatus status() {
        return status;
    }

    public Map<String, String> data() {
        return data;
    }

    public List<String> completedSteps() {
        return completedSteps;
    }

    public List<String> compensatedSteps() {
        return compensatedSteps;
    }

    public String failedStep() {
        return failedStep;
    }

    public FailureReason failureReason() {
        return failureReason;
    }

    /** Internal detail of the failure. For the saga state and the log only, never for a response. */
    public String detail() {
        return detail;
    }

    public int attempts() {
        return attempts;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
