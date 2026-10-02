package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corhuila.opti.workflow.application.port.in.SagaUseCases;
import co.edu.corhuila.opti.workflow.application.port.in.SagaUseCases.PlaceOrderInput;
import co.edu.corhuila.opti.workflow.application.port.out.Created;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.opti.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.opti.workflow.domain.saga.SagaType;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP adapter of the sagas, with the same contract as any -api: token, envelope, validation by
 * field, idempotent start ({@code 201} then {@code 200} for the same key). The response says which
 * step failed and a safe reason, never the internal detail of the failure.
 */
@RestController
@RequestMapping("/api/v1/sagas")
class SagaController {

    private static final String SAGAS = "/api/v1/sagas";

    private final SagaUseCases useCases;

    SagaController(SagaUseCases useCases) {
        this.useCases = useCases;
    }

    record PlaceOrderRequest(UUID patientId, UUID frameId, Integer quantity) {
    }

    record CancelOrderRequest(UUID orderId) {
    }

    /** Safe representation of a saga. The internal detail of a failure is deliberately not part of it. */
    record SagaResponse(UUID id, SagaType type, SagaStatus status, String orderId, List<String> completedSteps,
                        String failedStep, FailureReason failureReason, Instant createdAt, Instant updatedAt) {

        static SagaResponse from(SagaInstance s) {
            return new SagaResponse(s.id(), s.type(), s.status(), s.datum("orderId"), s.completedSteps(),
                    s.failedStep(), s.failureReason(), s.createdAt(), s.updatedAt());
        }
    }

    @PostMapping("/place-order")
    ResponseEntity<SagaResponse> placeOrder(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody PlaceOrderRequest body) {
        RequestRules.requireRole(http, Roles.ADMIN, Roles.SELLER);
        return respond(useCases.placeOrder(new PlaceOrderInput(body.patientId(), body.frameId(), body.quantity()), key));
    }

    @PostMapping("/cancel-order")
    ResponseEntity<SagaResponse> cancelOrder(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody CancelOrderRequest body) {
        RequestRules.requireRole(http, Roles.ADMIN, Roles.SELLER, Roles.SERVICE);
        return respond(useCases.cancelOrder(body.orderId(), key));
    }

    @GetMapping("/{id}")
    SagaResponse get(HttpServletRequest http, @PathVariable String id) {
        RequestRules.requireRole(http, Roles.ADMIN, Roles.SELLER, Roles.SERVICE);
        return SagaResponse.from(useCases.get(RequestRules.uuid(id, "id")));
    }

    private static ResponseEntity<SagaResponse> respond(Created<SagaInstance> result) {
        SagaResponse body = SagaResponse.from(result.value());
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(SAGAS + "/" + body.id())).body(body);
    }
}
