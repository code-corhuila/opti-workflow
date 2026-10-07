package co.edu.corhuila.opti.workflow.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.edu.corhuila.opti.workflow.application.port.in.SagaUseCases;
import co.edu.corhuila.opti.workflow.application.port.in.SagaUseCases.PlaceOrderInput;
import co.edu.corhuila.opti.workflow.domain.model.DomainException;
import co.edu.corhuila.opti.workflow.domain.model.ErrorKind;
import co.edu.corhuila.opti.workflow.domain.model.FieldError;
import co.edu.corhuila.opti.workflow.domain.model.ProductType;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.opti.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.opti.workflow.testsupport.Participants;
import co.edu.corhuila.opti.workflow.testsupport.SequentialIds;
import co.edu.corhuila.opti.workflow.testsupport.TestClock;

class SagaServiceTest {

    private static final UUID PATIENT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID FRAME = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final String KEY = "place-order-0001";

    private TestClock clock;
    private Participants.Patients patients;
    private Participants.Stock stock;
    private Participants.Orders orders;
    private Participants.Store store;
    private SagaUseCases service;

    @BeforeEach
    void setUp() {
        clock = TestClock.at("2026-09-29T15:00:00Z");
        patients = new Participants.Patients();
        patients.active.add(PATIENT);
        stock = new Participants.Stock();
        stock.stock.put(FRAME, 8);
        orders = new Participants.Orders();
        store = new Participants.Store();
        service = newService(store);
    }

    private SagaUseCases newService(Participants.Store sharedStore) {
        return new SagaService(patients, stock, orders, sharedStore, new SequentialIds(), clock, 3,
                Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    private static final UUID SELLER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static PlaceOrderInput input() {
        return new PlaceOrderInput(PATIENT, ProductType.FRAME, FRAME, 1, SELLER);
    }

    // ---- place-order, happy path ----------------------------------------------------------

    @Test
    void happyPathRunsTheStepsInOrderAndCompletes() {
        SagaInstance saga = service.placeOrder(input(), KEY).value();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.completedSteps()).containsExactly("check-patient", "reserve-stock", "open-order");
        assertThat(saga.failedStep()).isNull();
        assertThat(saga.datum("orderId")).isNotNull();
        assertThat(stock.stock.get(FRAME)).isEqualTo(7);
        assertThat(orders.lastDraft.unitPriceCents()).as("the price comes from the reservation").isEqualTo(52_000_000L);
        assertThat(orders.lastDraft.reservationId()).isEqualTo(UUID.fromString(saga.datum("reservationId")));
    }

    @Test
    void everyStepSendsItsOwnIdempotencyKeyDerivedFromTheSaga() {
        SagaInstance saga = service.placeOrder(input(), KEY).value();

        assertThat(stock.keys).containsExactly(saga.id() + ":reserve-stock");
        assertThat(orders.keys).containsExactly(saga.id() + ":open-order");
    }

    @Test
    void repeatingTheKeyReturnsTheSameSagaAndExecutesNothingAgain() {
        SagaInstance first = service.placeOrder(input(), KEY).value();
        var replay = service.placeOrder(input(), KEY);

        assertThat(replay.created()).isFalse();
        assertThat(replay.value().id()).isEqualTo(first.id());
        assertThat(patients.calls).hasSize(1);
        assertThat(stock.keys).hasSize(1);
        assertThat(orders.keys).hasSize(1);
    }

    // ---- place-order, refusals and compensations ------------------------------------------

    @Test
    void anUnknownPatientEndsCompensatedAtTheFirstStepWithoutTouchingStock() {
        patients.active.clear();

        SagaInstance saga = service.placeOrder(input(), KEY).value();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).isEqualTo("check-patient");
        assertThat(saga.failureReason()).isEqualTo(FailureReason.PATIENT_NOT_FOUND);
        assertThat(stock.keys).isEmpty();
    }

    @Test
    void anInactivePatientIsRefusedWithItsOwnReason() {
        patients.active.remove(PATIENT);
        patients.inactive.add(PATIENT);

        assertThat(service.placeOrder(input(), KEY).value().failureReason()).isEqualTo(FailureReason.PATIENT_NOT_ACTIVE);
    }

    @Test
    void insufficientStockEndsCompensatedAndReportsWhichStepFailed() {
        stock.stock.put(FRAME, 0);

        SagaInstance saga = service.placeOrder(input(), KEY).value();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).isEqualTo("reserve-stock");
        assertThat(saga.failureReason()).isEqualTo(FailureReason.INSUFFICIENT_STOCK);
        assertThat(saga.completedSteps()).containsExactly("check-patient");
        assertThat(orders.keys).isEmpty();
    }

    @Test
    void ifOpeningTheOrderFailsTheReservedStockIsReleased() {
        orders.refuseOpenWith = FailureReason.REJECTED;

        SagaInstance saga = service.placeOrder(input(), KEY).value();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).isEqualTo("open-order");
        assertThat(stock.stock.get(FRAME)).as("the stock is back").isEqualTo(8);
        assertThat(saga.compensatedSteps()).containsExactly("reserve-stock", "check-patient");
    }

    @Test
    void ifTheUndoItselfFailsTheSagaIsFailedAndExposedNeverHidden() {
        orders.refuseOpenWith = FailureReason.REJECTED;
        stock.failRelease = true;

        SagaInstance saga = service.placeOrder(input(), KEY).value();

        assertThat(saga.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(saga.failureReason()).isEqualTo(FailureReason.COMPENSATION_FAILED);
        assertThat(saga.failedStep()).isEqualTo("open-order");
        assertThat(saga.detail()).contains("reserve-stock");
    }

    // ---- temporary failures and resuming --------------------------------------------------

    @Test
    void aParticipantThatIsDownLeavesTheSagaRunningAndTheResumeFinishesIt() {
        stock.failReservesWithTechnicalFailure = 1;

        SagaInstance waiting = service.placeOrder(input(), KEY).value();

        assertThat(waiting.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(waiting.attempts()).isEqualTo(1);
        assertThat(waiting.completedSteps()).containsExactly("check-patient");

        clock.advance(Duration.ofMinutes(1));
        assertThat(service.resumeStuck()).isEqualTo(1);

        SagaInstance done = service.get(waiting.id());
        assertThat(done.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(patients.calls).as("the completed step is not repeated").hasSize(1);
        assertThat(stock.stock.get(FRAME)).isEqualTo(7);
    }

    @Test
    void aWorkflowThatRestartedResumesFromTheSavedState() {
        stock.failReservesWithTechnicalFailure = 1;
        SagaInstance waiting = service.placeOrder(input(), KEY).value();

        SagaUseCases afterRestart = newService(store); // new process, same persisted store
        clock.advance(Duration.ofMinutes(1));
        afterRestart.resumeStuck();

        assertThat(afterRestart.get(waiting.id()).status()).isEqualTo(SagaStatus.COMPLETED);
    }

    @Test
    void aSagaThatChangedRecentlyIsNotResumedYet() {
        stock.failReservesWithTechnicalFailure = 1;
        service.placeOrder(input(), KEY);

        assertThat(service.resumeStuck()).isZero();
    }

    @Test
    void aSagaHeldByAnotherThreadIsNotExecutedTwice() {
        stock.failReservesWithTechnicalFailure = 1;
        SagaInstance waiting = service.placeOrder(input(), KEY).value();
        store.lockedByAnother(waiting.id());
        clock.advance(Duration.ofMinutes(1));

        assertThat(service.resumeStuck()).isZero();
        assertThat(service.get(waiting.id()).status()).isEqualTo(SagaStatus.RUNNING);
    }

    @Test
    void afterTheAllowedAttemptsTheSagaIsFailedForAnOperator() {
        orders.openIsDown = true;
        SagaInstance saga = service.placeOrder(input(), KEY).value();
        for (int i = 0; i < 2; i++) {
            clock.advance(Duration.ofMinutes(1));
            service.resumeStuck();
        }

        SagaInstance last = service.get(saga.id());
        assertThat(last.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(last.failureReason()).isEqualTo(FailureReason.PARTICIPANT_UNAVAILABLE);
        assertThat(last.failedStep()).isEqualTo("open-order");
        assertThat(last.attempts()).isEqualTo(3);
    }

    // ---- cancel-order ---------------------------------------------------------------------

    @Test
    void cancellingAnOrderCancelsItAndReleasesItsStock() {
        UUID orderId = UUID.fromString(service.placeOrder(input(), KEY).value().datum("orderId"));

        SagaInstance saga = service.cancelOrder(orderId, "cancel-order-01").value();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(saga.completedSteps()).containsExactly("cancel-order", "release-stock");
        assertThat(orders.cancelled).contains(orderId);
        assertThat(stock.stock.get(FRAME)).isEqualTo(8);
    }

    @Test
    void cancellingAnUnknownOrderIsRefusedCleanly() {
        SagaInstance saga = service.cancelOrder(UUID.randomUUID(), "cancel-order-01").value();

        assertThat(saga.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(saga.failedStep()).isEqualTo("cancel-order");
        assertThat(saga.failureReason()).isEqualTo(FailureReason.ORDER_NOT_FOUND);
    }

    @Test
    void anOrderThatCannotBeCancelledLeavesTheStockAlone() {
        UUID orderId = UUID.fromString(service.placeOrder(input(), KEY).value().datum("orderId"));
        orders.cancelRefused = true;

        SagaInstance saga = service.cancelOrder(orderId, "cancel-order-01").value();

        assertThat(saga.failureReason()).isEqualTo(FailureReason.ORDER_NOT_CANCELLABLE);
        assertThat(stock.stock.get(FRAME)).as("stock stays reserved").isEqualTo(7);
    }

    @Test
    void ifReleasingTheStockFailsAfterCancellingTheSagaIsRetriedUntilItWorks() {
        UUID orderId = UUID.fromString(service.placeOrder(input(), KEY).value().datum("orderId"));
        stock.failRelease = true;

        SagaInstance waiting = service.cancelOrder(orderId, "cancel-order-01").value();
        assertThat(waiting.status()).isEqualTo(SagaStatus.RUNNING);
        assertThat(waiting.completedSteps()).containsExactly("cancel-order");

        stock.failRelease = false;
        clock.advance(Duration.ofMinutes(1));
        service.resumeStuck();

        assertThat(service.get(waiting.id()).status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(stock.stock.get(FRAME)).isEqualTo(8);
    }

    // ---- validation -----------------------------------------------------------------------

    @Test
    void invalidInputNamesEveryField() {
        assertThatThrownBy(() -> service.placeOrder(new PlaceOrderInput(null, null, null, 11, null), "short"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ErrorKind.VALIDATION);
                    assertThat(e.fields()).extracting(FieldError::field).containsExactlyInAnyOrder(
                            "Idempotency-Key", "patientId", "productType", "productId", "quantity", "sellerId");
                });
        assertThatThrownBy(() -> service.cancelOrder(null, "short")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.fields()).extracting(FieldError::field)
                        .containsExactlyInAnyOrder("Idempotency-Key", "orderId"));
    }

    @Test
    void unknownSagaIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.kind()).isEqualTo(ErrorKind.NOT_FOUND));
    }

    @Test
    void resumingWithNothingStuckDoesNothing() {
        assertThat(service.resumeStuck()).isZero();
    }
}
