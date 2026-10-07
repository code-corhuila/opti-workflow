package co.edu.corhuila.opti.workflow.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;

/** Where sagas live. It must survive a restart: that is what makes a saga resumable. */
public interface SagaStore {

    /**
     * Stores the saga under its idempotency key unless the key was already used, atomically. When
     * the key exists nothing is stored and the saga created the first time is returned.
     */
    Created<SagaInstance> createIfAbsent(String idempotencyKey, SagaInstance saga);

    Optional<SagaInstance> find(UUID id);

    /** Saves the state; called after every step. */
    void save(SagaInstance saga);

    /** Sagas still RUNNING or COMPENSATING whose last change is older than the given moment. */
    List<UUID> resumable(Instant notChangedSince, int limit);

    /** Takes the right to execute the saga for a while; false when someone else holds it. */
    boolean tryLock(UUID id, Duration lease);

    void unlock(UUID id);
}
