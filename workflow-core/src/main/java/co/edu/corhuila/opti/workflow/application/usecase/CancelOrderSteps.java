package co.edu.corhuila.opti.workflow.application.usecase;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import co.edu.corhuila.opti.workflow.application.port.out.OrdersPort;
import co.edu.corhuila.opti.workflow.application.port.out.StockPort;
import co.edu.corhuila.opti.workflow.domain.model.ProductType;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;

/**
 * The steps of cancel-order: cancel the order (it cannot be undone), then give the reserved stock
 * back. Cancelling is the gatekeeper: if the order cannot be cancelled nothing else happens. If
 * releasing the stock fails afterwards there is nothing to undo, so the saga is retried until it
 * succeeds and, if it never does, it ends FAILED for an operator.
 */
final class CancelOrderSteps {

    static final String CANCEL_ORDER = "cancel-order";
    static final String RELEASE_STOCK = "release-stock";

    static final String ORDER_ID = "orderId";
    /** Each entry {@code reservationId:productType}, comma-separated (HU-25: not every line is a frame). */
    static final String RESERVATIONS = "reservations";

    private CancelOrderSteps() {
    }

    static List<Step> of(OrdersPort orders, StockPort stock) {
        return List.of(new CancelOrder(orders), new ReleaseStock(stock));
    }

    private record CancelOrder(OrdersPort orders) implements Step {

        @Override
        public String name() {
            return CANCEL_ORDER;
        }

        @Override
        public Map<String, String> execute(SagaInstance saga) {
            UUID orderId = UUID.fromString(saga.datum(ORDER_ID));
            OrdersPort.Snapshot order = orders.get(orderId);
            orders.cancel(orderId);
            String encoded = order.reservations().stream()
                    .map(r -> r.reservationId() + ":" + r.productType())
                    .collect(Collectors.joining(","));
            return Map.of(RESERVATIONS, encoded);
        }

        @Override
        public boolean compensable() {
            return false;
        }
    }

    private record ReleaseStock(StockPort stock) implements Step {

        @Override
        public String name() {
            return RELEASE_STOCK;
        }

        @Override
        public Map<String, String> execute(SagaInstance saga) {
            String encoded = saga.datum(RESERVATIONS);
            if (encoded != null && !encoded.isBlank()) {
                Arrays.stream(encoded.split(",")).forEach(entry -> {
                    String[] parts = entry.split(":", 2);
                    stock.release(ProductType.valueOf(parts[1]), UUID.fromString(parts[0]));
                });
            }
            return Map.of();
        }

        @Override
        public boolean compensable() {
            return false;
        }
    }
}
