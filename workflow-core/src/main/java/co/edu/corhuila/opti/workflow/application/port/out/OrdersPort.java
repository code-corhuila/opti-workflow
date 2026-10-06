package co.edu.corhuila.opti.workflow.application.port.out;

import java.util.List;
import java.util.UUID;

import co.edu.corhuila.opti.workflow.domain.model.ProductType;

/** The sales domain as the workflow sees it. */
public interface OrdersPort {

    /** Opens a quotation from a stock reservation. The key makes a retried call return the same order. */
    UUID open(Draft draft, String idempotencyKey);

    Snapshot get(UUID orderId);

    /** Cancels the order (and its invoice). Cancelling twice is harmless. */
    void cancel(UUID orderId);

    /** What the workflow tells sales to create; the price comes from the reservation, never from the client. */
    record Draft(UUID patientId, String reference, ProductType productType, UUID productId, UUID reservationId,
                 String sku, String description, int quantity, long unitPriceCents, UUID sellerId) {
    }

    /** What the workflow needs to know of an existing order, to release each line's reservation on cancel. */
    record Snapshot(UUID id, String status, List<ReservationRef> reservations) {
    }

    /** One line's reservation, with the product type needed to release it from the right catalog. */
    record ReservationRef(UUID reservationId, ProductType productType) {
    }
}
