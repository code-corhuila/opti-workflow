package co.edu.corhuila.opti.workflow.adapter.out.participants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.opti.workflow.application.port.out.OrdersPort;
import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.opti.workflow.domain.model.ProductType;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;

/** The sales domain through its published API. */
public class OrdersHttpClient implements OrdersPort {

    private final ParticipantClient client;
    private final String baseUrl;

    public OrdersHttpClient(ParticipantClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public UUID open(Draft draft, String idempotencyKey) {
        Map<String, Object> line = Map.of("productType", draft.productType().name(), "productId", draft.productId(),
                "reservationId", draft.reservationId(), "sku", draft.sku(), "description", draft.description(),
                "quantity", draft.quantity(), "unitPriceCents", draft.unitPriceCents());
        JsonNode body = client.call("POST", baseUrl + "/api/v1/work-orders",
                Map.of("patientId", draft.patientId(), "reference", draft.reference(), "items", List.of(line),
                        "sellerId", draft.sellerId()),
                idempotencyKey, refusal -> ParticipantFailure.business(FailureReason.REJECTED,
                        "sales refused the order: " + refusal.message()));
        return UUID.fromString(body.path("id").asText());
    }

    @Override
    public Snapshot get(UUID orderId) {
        JsonNode order = client.call("GET", baseUrl + "/api/v1/work-orders/" + orderId, null, null,
                this::orderRefusal);
        List<ReservationRef> reservations = new ArrayList<>();
        order.path("items").forEach(item -> reservations.add(new ReservationRef(
                UUID.fromString(item.path("reservationId").asText()),
                ProductType.valueOf(item.path("productType").asText()))));
        return new Snapshot(orderId, order.path("status").asText(), reservations);
    }

    @Override
    public void cancel(UUID orderId) {
        client.call("POST", baseUrl + "/api/v1/work-orders/" + orderId + "/cancel", null, null, this::orderRefusal);
    }

    private ParticipantFailure orderRefusal(ParticipantClient.Refusal refusal) {
        return switch (refusal.status()) {
            case 404 -> ParticipantFailure.business(FailureReason.ORDER_NOT_FOUND, "order not found");
            case 422 -> ParticipantFailure.business(FailureReason.ORDER_NOT_CANCELLABLE,
                    "sales refused: " + refusal.message());
            default -> ParticipantFailure.business(FailureReason.REJECTED, "sales refused: " + refusal.message());
        };
    }
}
