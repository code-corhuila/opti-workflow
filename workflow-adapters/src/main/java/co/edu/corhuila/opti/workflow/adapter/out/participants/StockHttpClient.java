package co.edu.corhuila.opti.workflow.adapter.out.participants;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.opti.workflow.application.port.out.StockPort;
import co.edu.corhuila.opti.workflow.domain.model.ProductType;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;

/**
 * The products domain through its published API. Each product type owns its own catalog path
 * (frames, lenses, accessories, liquids); only Frame's release lives under the shared
 * {@code /reservations/{id}/release} (a historical choice in products-api) while the other three
 * each have their own {@code /{catalog}/reservations/{id}/release} — see each controller there.
 */
public class StockHttpClient implements StockPort {

    private final ParticipantClient client;
    private final String baseUrl;

    public StockHttpClient(ParticipantClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public Reservation reserve(ProductType productType, UUID productId, int quantity, String reference,
                                String idempotencyKey) {
        JsonNode body = client.call("POST",
                baseUrl + "/api/v1/" + catalog(productType) + "/" + productId + "/reservations",
                Map.of("quantity", quantity, "reference", reference), idempotencyKey, refusal -> {
                    if (refusal.status() == 404) {
                        return ParticipantFailure.business(FailureReason.FRAME_NOT_FOUND,
                                productType + " " + productId + " not found");
                    }
                    if (refusal.status() == 422 && refusal.message().contains("insufficient stock")) {
                        return ParticipantFailure.business(FailureReason.INSUFFICIENT_STOCK, refusal.message());
                    }
                    return ParticipantFailure.business(FailureReason.REJECTED, "products refused: " + refusal.message());
                });
        return new Reservation(UUID.fromString(body.path("id").asText()), productType, productId,
                body.path("sku").asText(), body.path("description").asText(), body.path("quantity").asInt(),
                body.path("unitPriceCents").asLong());
    }

    @Override
    public void release(ProductType productType, UUID reservationId) {
        client.call("POST", baseUrl + "/api/v1/" + releasePath(productType, reservationId), null, null, refusal ->
                ParticipantFailure.business(FailureReason.REJECTED, "products refused the release: " + refusal.message()));
    }

    private static String catalog(ProductType productType) {
        return switch (productType) {
            case FRAME -> "frames";
            case LENS -> "lenses";
            case ACCESSORY -> "accessories";
            case LIQUID -> "liquids";
        };
    }

    private static String releasePath(ProductType productType, UUID reservationId) {
        return switch (productType) {
            case FRAME -> "reservations/" + reservationId + "/release";
            case LENS -> "lenses/reservations/" + reservationId + "/release";
            case ACCESSORY -> "accessories/reservations/" + reservationId + "/release";
            case LIQUID -> "liquids/reservations/" + reservationId + "/release";
        };
    }
}
