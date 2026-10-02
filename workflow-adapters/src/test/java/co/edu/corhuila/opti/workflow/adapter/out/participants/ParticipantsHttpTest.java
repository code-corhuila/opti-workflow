package co.edu.corhuila.opti.workflow.adapter.out.participants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import co.edu.corhuila.opti.workflow.adapter.Correlation;
import co.edu.corhuila.opti.workflow.application.port.out.OrdersPort;
import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;

/** The participant clients against a fake API that speaks the common contract. */
class ParticipantsHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String base;
    private final List<String[]> received = new CopyOnWriteArrayList<>();
    private final Queue<int[]> statuses = new LinkedList<>();
    private final Queue<String> bodies = new LinkedList<>();
    private final List<Duration> sleeps = new ArrayList<>();
    private ParticipantClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new ParticipantClient(HttpClient.newHttpClient(), JSON, "service-token", Duration.ofSeconds(2), 3,
                Duration.ofMillis(200), sleeps::add, () -> 0.5);
        MDC.put(Correlation.MDC_KEY, "run-42");
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.clear();
    }

    private void reply(int status, String body) {
        statuses.add(new int[] {status});
        bodies.add(body);
    }

    private void answer(HttpExchange exchange) throws IOException {
        received.add(new String[] {exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                exchange.getRequestHeaders().getFirst("Idempotency-Key")});
        exchange.getRequestBody().readAllBytes();
        int status = statuses.isEmpty() ? 200 : statuses.poll()[0];
        String body = bodies.isEmpty() ? "{}" : bodies.poll();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String envelope(String message) {
        return "{\"error\":\"X\",\"message\":\"" + message + "\",\"traceId\":\"t\"}";
    }

    // ---- headers and retries --------------------------------------------------------------

    @Test
    void everyCallCarriesTheWorkflowTokenTheCorrelationIdAndTheStepKey() {
        var stock = new StockHttpClient(client, base);
        UUID frame = UUID.randomUUID();
        reply(201, "{\"id\":\"" + UUID.randomUUID() + "\",\"sku\":\"RB5228-2000\",\"description\":\"Frame\","
                + "\"quantity\":1,\"unitPriceCents\":52000000}");

        stock.reserve(frame, 1, "saga-1", "saga-1:reserve-stock");

        String[] call = received.get(0);
        assertThat(call[0]).isEqualTo("POST");
        assertThat(call[1]).isEqualTo("/api/v1/frames/" + frame + "/reservations");
        assertThat(call[2]).isEqualTo("Bearer service-token");
        assertThat(call[3]).isEqualTo("run-42");
        assertThat(call[4]).isEqualTo("saga-1:reserve-stock");
    }

    @Test
    void temporaryFailuresAreRetriedWithGrowingJitterThenReportedAsTechnical() {
        for (int i = 0; i < 3; i++) {
            reply(503, envelope("down"));
        }

        assertThatThrownBy(() -> new PatientsHttpClient(client, base).requireActive(UUID.randomUUID()))
                .isInstanceOfSatisfying(ParticipantFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(ParticipantFailure.Kind.TECHNICAL));
        assertThat(received).hasSize(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(200), Duration.ofMillis(400));
    }

    @Test
    void aBusinessRefusalIsNotRetried() {
        reply(422, envelope("insufficient stock: 0 available, 1 requested"));

        assertThatThrownBy(() -> new StockHttpClient(client, base).reserve(UUID.randomUUID(), 1, "s", "k12345678"))
                .isInstanceOfSatisfying(ParticipantFailure.class, e -> {
                    assertThat(e.kind()).isEqualTo(ParticipantFailure.Kind.BUSINESS);
                    assertThat(e.reason()).isEqualTo(FailureReason.INSUFFICIENT_STOCK);
                });
        assertThat(received).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void aRejectedServiceCredentialIsAnOperationsProblemNotABusinessRefusal() {
        reply(403, envelope("forbidden"));

        assertThatThrownBy(() -> new PatientsHttpClient(client, base).requireActive(UUID.randomUUID()))
                .isInstanceOfSatisfying(ParticipantFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(ParticipantFailure.Kind.TECHNICAL));
        assertThat(received).hasSize(1);
    }

    @Test
    void aServerThatIsGoneIsATechnicalFailure() {
        server.stop(0);

        assertThatThrownBy(() -> new OrdersHttpClient(client, base).cancel(UUID.randomUUID()))
                .isInstanceOfSatisfying(ParticipantFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(ParticipantFailure.Kind.TECHNICAL));
    }

    // ---- classification of each participant -----------------------------------------------

    @Test
    void patientStatesAreClassified() {
        var patients = new PatientsHttpClient(client, base);
        reply(404, envelope("patient not found"));
        assertThatThrownBy(() -> patients.requireActive(UUID.randomUUID())).isInstanceOfSatisfying(
                ParticipantFailure.class, e -> assertThat(e.reason()).isEqualTo(FailureReason.PATIENT_NOT_FOUND));

        reply(200, "{\"status\":\"INACTIVE\",\"fullName\":\"X Y\"}");
        assertThatThrownBy(() -> patients.requireActive(UUID.randomUUID())).isInstanceOfSatisfying(
                ParticipantFailure.class, e -> assertThat(e.reason()).isEqualTo(FailureReason.PATIENT_NOT_ACTIVE));

        reply(200, "{\"status\":\"CONTROL_OVERDUE\",\"fullName\":\"Laura Ortega\"}");
        assertThat(patients.requireActive(UUID.randomUUID())).isEqualTo("Laura Ortega");
    }

    @Test
    void orderRefusalsAreClassified() {
        var orders = new OrdersHttpClient(client, base);
        reply(404, envelope("work order not found"));
        assertThatThrownBy(() -> orders.get(UUID.randomUUID())).isInstanceOfSatisfying(ParticipantFailure.class,
                e -> assertThat(e.reason()).isEqualTo(FailureReason.ORDER_NOT_FOUND));

        reply(422, envelope("an order that is DELIVERED cannot be cancelled"));
        assertThatThrownBy(() -> orders.cancel(UUID.randomUUID())).isInstanceOfSatisfying(ParticipantFailure.class,
                e -> assertThat(e.reason()).isEqualTo(FailureReason.ORDER_NOT_CANCELLABLE));

        UUID id = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        reply(200, "{\"id\":\"" + id + "\",\"status\":\"QUOTATION\",\"items\":[{\"reservationId\":\"" + reservation + "\"}]}");
        OrdersPort.Snapshot snapshot = orders.get(id);
        assertThat(snapshot.reservationIds()).containsExactly(reservation);
        assertThat(snapshot.status()).isEqualTo("QUOTATION");
    }

    @Test
    void openingAnOrderSendsThePriceAndReservationAndReadsTheNewId() {
        UUID orderId = UUID.randomUUID();
        reply(201, "{\"id\":\"" + orderId + "\"}");

        UUID opened = new OrdersHttpClient(client, base).open(new OrdersPort.Draft(UUID.randomUUID(), "saga-1",
                UUID.randomUUID(), UUID.randomUUID(), "RB5228-2000", "Frame", 1, 52_000_000L), "saga-1:open-order");

        assertThat(opened).isEqualTo(orderId);
        assertThat(received.get(0)[4]).isEqualTo("saga-1:open-order");
    }
}
