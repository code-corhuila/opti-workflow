package co.edu.corhuila.opti.workflow.application.port.out;

import java.util.List;
import java.util.UUID;

/** The sales domain as the workflow sees it. */
public interface OrdersPort {

    /** Opens a quotation from a stock reservation. The key makes a retried call return the same order. */
    UUID open(Draft draft, String idempotencyKey);

    Snapshot get(UUID orderId);

    /** Cancels the order (and its invoice). Cancelling twice is harmless. */
    void cancel(UUID orderId);

    /** What the workflow tells sales to create; the price comes from the reservation, never from the client. */
    record Draft(UUID patientId, String reference, UUID frameId, UUID reservationId, String sku, String description,
                 int quantity, long unitPriceCents) {
    }

    /** What the workflow needs to know of an existing order. */
    record Snapshot(UUID id, String status, List<UUID> reservationIds) {
    }
}
