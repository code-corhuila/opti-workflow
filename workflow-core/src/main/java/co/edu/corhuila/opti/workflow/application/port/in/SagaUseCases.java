package co.edu.corhuila.opti.workflow.application.port.in;

import java.util.UUID;

import co.edu.corhuila.opti.workflow.application.port.out.Created;
import co.edu.corhuila.opti.workflow.domain.model.ProductType;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;

/** What the workflow offers: start a cross-domain process, follow it, and resume the ones that stopped. */
public interface SagaUseCases {

    /** Reserves stock and opens a quotation for a patient. Repeating the key returns the same saga. */
    Created<SagaInstance> placeOrder(PlaceOrderInput input, String idempotencyKey);

    /** Cancels an order and gives its stock back. Repeating the key returns the same saga. */
    Created<SagaInstance> cancelOrder(UUID orderId, String idempotencyKey);

    SagaInstance get(UUID id);

    /** Resumes the sagas that stopped halfway (a participant was down, the workflow restarted). Returns how many. */
    int resumeStuck();

    /** Raw input of place-order, before validation. {@code sellerId} is the caller who started it. */
    record PlaceOrderInput(UUID patientId, ProductType productType, UUID productId, Integer quantity, UUID sellerId) {
    }
}
