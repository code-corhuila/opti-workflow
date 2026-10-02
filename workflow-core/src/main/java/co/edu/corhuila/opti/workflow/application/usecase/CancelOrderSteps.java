package co.edu.corhuila.opti.workflow.application.usecase;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import co.edu.corhuila.opti.workflow.application.port.out.OrdersPort;
import co.edu.corhuila.opti.workflow.application.port.out.StockPort;
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
    static final String RESERVATION_IDS = "reservationIds";

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
            return Map.of(RESERVATION_IDS, order.reservationIds().stream().map(UUID::toString)
                    .collect(Collectors.joining(",")));
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
            String ids = saga.datum(RESERVATION_IDS);
            if (ids != null && !ids.isBlank()) {
                Arrays.stream(ids.split(",")).map(UUID::fromString).forEach(stock::release);
            }
            return Map.of();
        }

        @Override
        public boolean compensable() {
            return false;
        }
    }
}
