package co.edu.corhuila.opti.workflow.application.port.out;

import java.util.UUID;

/** The products domain as the workflow sees it: reserve stock and give it back. */
public interface StockPort {

    /** Holds units of a frame. The key makes a retried call return the same reservation. */
    Reservation reserve(UUID frameId, int quantity, String reference, String idempotencyKey);

    /** Gives the units back. Safe to repeat. */
    void release(UUID reservationId);

    /** The reservation with the price and description the products domain agreed at that moment. */
    record Reservation(UUID id, UUID frameId, String sku, String description, int quantity, long unitPriceCents) {
    }
}
