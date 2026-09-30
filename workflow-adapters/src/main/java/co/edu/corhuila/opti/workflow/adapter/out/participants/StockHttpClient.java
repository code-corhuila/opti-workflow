package co.edu.corhuila.opti.workflow.adapter.out.participants;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.opti.workflow.application.port.out.StockPort;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;

/** The products domain through its published API. */
public class StockHttpClient implements StockPort {

    private final ParticipantClient client;
    private final String baseUrl;

    public StockHttpClient(ParticipantClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public Reservation reserve(UUID frameId, int quantity, String reference, String idempotencyKey) {
        JsonNode body = client.call("POST", baseUrl + "/api/v1/frames/" + frameId + "/reservations",
                Map.of("quantity", quantity, "reference", reference), idempotencyKey, refusal -> {
                    if (refusal.status() == 404) {
                        return ParticipantFailure.business(FailureReason.FRAME_NOT_FOUND, "frame " + frameId + " not found");
                    }
                    if (refusal.status() == 422 && refusal.message().contains("insufficient stock")) {
                        return ParticipantFailure.business(FailureReason.INSUFFICIENT_STOCK, refusal.message());
                    }
                    return ParticipantFailure.business(FailureReason.REJECTED, "products refused: " + refusal.message());
                });
        return new Reservation(UUID.fromString(body.path("id").asText()), frameId, body.path("sku").asText(),
                body.path("description").asText(), body.path("quantity").asInt(), body.path("unitPriceCents").asLong());
    }

    @Override
    public void release(UUID reservationId) {
        client.call("POST", baseUrl + "/api/v1/reservations/" + reservationId + "/release", null, null, refusal ->
                ParticipantFailure.business(FailureReason.REJECTED, "products refused the release: " + refusal.message()));
    }
}
