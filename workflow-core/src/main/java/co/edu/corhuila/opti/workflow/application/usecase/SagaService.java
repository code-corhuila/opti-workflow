package co.edu.corhuila.opti.workflow.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import co.edu.corhuila.opti.workflow.application.port.in.SagaUseCases;
import co.edu.corhuila.opti.workflow.application.port.out.Created;
import co.edu.corhuila.opti.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.opti.workflow.application.port.out.OrdersPort;
import co.edu.corhuila.opti.workflow.application.port.out.PatientsPort;
import co.edu.corhuila.opti.workflow.application.port.out.SagaStore;
import co.edu.corhuila.opti.workflow.application.port.out.StockPort;
import co.edu.corhuila.opti.workflow.domain.model.DomainException;
import co.edu.corhuila.opti.workflow.domain.model.Validation;
import co.edu.corhuila.opti.workflow.domain.model.Violations;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.opti.workflow.domain.saga.SagaType;

/**
 * Starts sagas idempotently and resumes the ones that stopped. The start saves the saga together
 * with its key in one atomic operation; a repeated key returns the same saga and executes nothing.
 */
public class SagaService implements SagaUseCases {

    private static final int MAX_QUANTITY = 10;
    private static final int RESUME_BATCH = 20;

    private final SagaStore store;
    private final SagaOrchestrator orchestrator;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration lease;
    private final Duration resumeAfter;

    public SagaService(PatientsPort patients, StockPort stock, OrdersPort orders, SagaStore store, IdGenerator ids,
                       Clock clock, int maxAttempts, Duration lease, Duration resumeAfter) {
        this.store = store;
        this.ids = ids;
        this.clock = clock;
        this.lease = lease;
        this.resumeAfter = resumeAfter;
        this.orchestrator = new SagaOrchestrator(Map.of(
                SagaType.PLACE_ORDER, PlaceOrderSteps.of(patients, stock, orders),
                SagaType.CANCEL_ORDER, CancelOrderSteps.of(orders, stock)), store, clock, maxAttempts);
    }

    @Override
    public Created<SagaInstance> placeOrder(PlaceOrderInput input, String idempotencyKey) {
        Violations v = new Violations();
        String key = v.check(() -> Validation.idempotencyKey(idempotencyKey));
        UUID patient = v.check(() -> Validation.required(input.patientId(), "patientId"));
        UUID frame = v.check(() -> Validation.required(input.frameId(), "frameId"));
        Integer quantity = v.check(() -> Validation.intBetween(
                Validation.required(input.quantity(), "quantity"), "quantity", 1, MAX_QUANTITY));
        UUID seller = v.check(() -> Validation.required(input.sellerId(), "sellerId"));
        v.throwIfAny();
        return start(key, SagaType.PLACE_ORDER, Map.of(PlaceOrderSteps.PATIENT_ID, patient.toString(),
                PlaceOrderSteps.FRAME_ID, frame.toString(), PlaceOrderSteps.QUANTITY, quantity.toString(),
                PlaceOrderSteps.SELLER_ID, seller.toString()));
    }

    @Override
    public Created<SagaInstance> cancelOrder(UUID orderId, String idempotencyKey) {
        Violations v = new Violations();
        String key = v.check(() -> Validation.idempotencyKey(idempotencyKey));
        UUID order = v.check(() -> Validation.required(orderId, "orderId"));
        v.throwIfAny();
        return start(key, SagaType.CANCEL_ORDER, Map.of(CancelOrderSteps.ORDER_ID, order.toString()));
    }

    @Override
    public SagaInstance get(UUID id) {
        return store.find(id).orElseThrow(() -> DomainException.notFound("saga not found"));
    }

    @Override
    public int resumeStuck() {
        List<UUID> stuck = store.resumable(clock.instant().minus(resumeAfter), RESUME_BATCH);
        int resumed = 0;
        for (UUID id : stuck) {
            SagaInstance saga = store.find(id).orElse(null);
            if (saga != null && !saga.status().isFinal() && execute(saga) != saga) {
                resumed++;
            }
        }
        return resumed;
    }

    private Created<SagaInstance> start(String key, SagaType type, Map<String, String> input) {
        SagaInstance saga = SagaInstance.start(ids.next(), type, input, clock.instant());
        Created<SagaInstance> stored = store.createIfAbsent(key, saga);
        if (!stored.created()) {
            return stored;
        }
        return new Created<>(execute(saga), true);
    }

    /** Runs under a lease so a resume cannot execute a saga that another thread is executing. */
    private SagaInstance execute(SagaInstance saga) {
        if (!store.tryLock(saga.id(), lease)) {
            return saga;
        }
        try {
            return orchestrator.run(saga);
        } finally {
            store.unlock(saga.id());
        }
    }
}
