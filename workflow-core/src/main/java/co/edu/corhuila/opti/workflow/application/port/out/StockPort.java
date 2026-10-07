package co.edu.corhuila.opti.workflow.application.port.out;

import java.util.UUID;

import co.edu.corhuila.opti.workflow.domain.model.ProductType;

/** The products domain as the workflow sees it: reserve stock and give it back. */
public interface StockPort {

    /** Holds units of a frame/lens/accessory/liquid. The key makes a retried call return the same reservation. */
    Reservation reserve(ProductType productType, UUID productId, int quantity, String reference, String idempotencyKey);

    /** Gives the units back. Safe to repeat. {@code productType} picks the right release endpoint. */
    void release(ProductType productType, UUID reservationId);

    /** The reservation with the price and description the products domain agreed at that moment. */
    record Reservation(UUID id, ProductType productType, UUID productId, String sku, String description,
                       int quantity, long unitPriceCents) {
    }
}
