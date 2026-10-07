package co.edu.corhuila.opti.workflow.domain.model;

/**
 * Which products-domain catalog a place-order request's product id belongs to (HU-25: a sale is
 * no longer only a frame). Mirrors the enum of the same name in products-api and sales-api.
 */
public enum ProductType {
    FRAME,
    LENS,
    ACCESSORY,
    LIQUID
}
