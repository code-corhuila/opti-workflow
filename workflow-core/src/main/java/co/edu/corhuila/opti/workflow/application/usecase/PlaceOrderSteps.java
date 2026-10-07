package co.edu.corhuila.opti.workflow.application.usecase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import co.edu.corhuila.opti.workflow.application.port.out.OrdersPort;
import co.edu.corhuila.opti.workflow.application.port.out.PatientsPort;
import co.edu.corhuila.opti.workflow.application.port.out.StockPort;
import co.edu.corhuila.opti.workflow.domain.model.ProductType;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;

/**
 * The steps of place-order, each declared next to its compensation:
 * check the patient (nothing to undo), reserve the stock (release it), open the order (cancel it).
 */
final class PlaceOrderSteps {

    static final String CHECK_PATIENT = "check-patient";
    static final String RESERVE_STOCK = "reserve-stock";
    static final String OPEN_ORDER = "open-order";

    static final String PATIENT_ID = "patientId";
    static final String PRODUCT_TYPE = "productType";
    static final String PRODUCT_ID = "productId";
    static final String QUANTITY = "quantity";
    static final String SELLER_ID = "sellerId";
    static final String PATIENT_NAME = "patientName";
    static final String RESERVATION_ID = "reservationId";
    static final String ORDER_ID = "orderId";

    private PlaceOrderSteps() {
    }

    static List<Step> of(PatientsPort patients, StockPort stock, OrdersPort orders) {
        return List.of(new CheckPatient(patients), new ReserveStock(stock), new OpenOrder(orders));
    }

    private record CheckPatient(PatientsPort patients) implements Step {

        @Override
        public String name() {
            return CHECK_PATIENT;
        }

        @Override
        public Map<String, String> execute(SagaInstance saga) {
            return Map.of(PATIENT_NAME, patients.requireActive(UUID.fromString(saga.datum(PATIENT_ID))));
        }
    }

    private record ReserveStock(StockPort stock) implements Step {

        @Override
        public String name() {
            return RESERVE_STOCK;
        }

        @Override
        public Map<String, String> execute(SagaInstance saga) {
            var reservation = stock.reserve(ProductType.valueOf(saga.datum(PRODUCT_TYPE)),
                    UUID.fromString(saga.datum(PRODUCT_ID)), Integer.parseInt(saga.datum(QUANTITY)),
                    saga.id().toString(), saga.id() + ":" + RESERVE_STOCK);
            return Map.of(RESERVATION_ID, reservation.id().toString(), "sku", reservation.sku(),
                    "description", reservation.description(),
                    "unitPriceCents", String.valueOf(reservation.unitPriceCents()));
        }

        @Override
        public void compensate(SagaInstance saga) {
            String reservation = saga.datum(RESERVATION_ID);
            if (reservation != null) {
                stock.release(ProductType.valueOf(saga.datum(PRODUCT_TYPE)), UUID.fromString(reservation));
            }
        }
    }

    private record OpenOrder(OrdersPort orders) implements Step {

        @Override
        public String name() {
            return OPEN_ORDER;
        }

        @Override
        public Map<String, String> execute(SagaInstance saga) {
            var draft = new OrdersPort.Draft(UUID.fromString(saga.datum(PATIENT_ID)), saga.id().toString(),
                    ProductType.valueOf(saga.datum(PRODUCT_TYPE)), UUID.fromString(saga.datum(PRODUCT_ID)),
                    UUID.fromString(saga.datum(RESERVATION_ID)), saga.datum("sku"), saga.datum("description"),
                    Integer.parseInt(saga.datum(QUANTITY)), Long.parseLong(saga.datum("unitPriceCents")),
                    UUID.fromString(saga.datum(SELLER_ID)));
            return Map.of(ORDER_ID, orders.open(draft, saga.id() + ":" + OPEN_ORDER).toString());
        }

        @Override
        public void compensate(SagaInstance saga) {
            String order = saga.datum(ORDER_ID);
            if (order != null) {
                orders.cancel(UUID.fromString(order));
            }
        }
    }
}
