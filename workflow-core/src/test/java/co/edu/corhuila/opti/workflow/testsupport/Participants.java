package co.edu.corhuila.opti.workflow.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.edu.corhuila.opti.workflow.application.port.out.Created;
import co.edu.corhuila.opti.workflow.application.port.out.OrdersPort;
import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.opti.workflow.application.port.out.PatientsPort;
import co.edu.corhuila.opti.workflow.application.port.out.SagaStore;
import co.edu.corhuila.opti.workflow.application.port.out.StockPort;
import co.edu.corhuila.opti.workflow.domain.model.ProductType;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;

/** Fakes of the three domains and of the saga store, with switches to make each one fail on demand. */
public final class Participants {

    private Participants() {
    }

    /** Customers. */
    public static class Patients implements PatientsPort {

        public final Set<UUID> active = new HashSet<>();
        public final Set<UUID> inactive = new HashSet<>();
        public final List<String> calls = new ArrayList<>();
        public boolean down;

        @Override
        public String requireActive(UUID patientId) {
            calls.add("requireActive");
            if (down) {
                throw ParticipantFailure.technical("customers is down");
            }
            if (inactive.contains(patientId)) {
                throw ParticipantFailure.business(FailureReason.PATIENT_NOT_ACTIVE, "patient inactive");
            }
            if (!active.contains(patientId)) {
                throw ParticipantFailure.business(FailureReason.PATIENT_NOT_FOUND, "no such patient");
            }
            return "Laura Ortega";
        }
    }

    /** Products: stock per frame, reservations bound to their idempotency key like the real service does. */
    public static class Stock implements StockPort {

        public final Map<UUID, Integer> stock = new HashMap<>();
        public final Map<UUID, Reservation> reservations = new LinkedHashMap<>();
        public final Set<UUID> released = new HashSet<>();
        public final List<String> keys = new ArrayList<>();
        public int failReservesWithTechnicalFailure;
        public boolean failRelease;
        private final Map<String, UUID> byKey = new HashMap<>();

        @Override
        public Reservation reserve(ProductType productType, UUID productId, int quantity, String reference,
                                    String idempotencyKey) {
            keys.add(idempotencyKey);
            if (failReservesWithTechnicalFailure > 0) {
                failReservesWithTechnicalFailure--;
                throw ParticipantFailure.technical("products is down");
            }
            UUID existing = byKey.get(idempotencyKey);
            if (existing != null) {
                return reservations.get(existing);
            }
            int available = stock.getOrDefault(productId, -1);
            if (available < 0) {
                throw ParticipantFailure.business(FailureReason.FRAME_NOT_FOUND, "no such " + productType);
            }
            if (available < quantity) {
                throw ParticipantFailure.business(FailureReason.INSUFFICIENT_STOCK, "only " + available);
            }
            stock.put(productId, available - quantity);
            Reservation reservation = new Reservation(UUID.randomUUID(), productType, productId, "RB5228-2000",
                    "Frame Ray-Ban", quantity, 52_000_000L);
            reservations.put(reservation.id(), reservation);
            byKey.put(idempotencyKey, reservation.id());
            return reservation;
        }

        @Override
        public void release(ProductType productType, UUID reservationId) {
            if (failRelease) {
                throw ParticipantFailure.technical("products is down");
            }
            Reservation reservation = reservations.get(reservationId);
            if (reservation == null) {
                throw ParticipantFailure.business(FailureReason.REJECTED, "no such reservation");
            }
            if (released.add(reservationId)) {
                stock.merge(reservation.productId(), reservation.quantity(), Integer::sum);
            }
        }
    }

    /** Sales. */
    public static class Orders implements OrdersPort {

        public final Map<UUID, Snapshot> orders = new LinkedHashMap<>();
        public final Set<UUID> cancelled = new HashSet<>();
        public final List<String> keys = new ArrayList<>();
        public Draft lastDraft;
        public FailureReason refuseOpenWith;
        public boolean openIsDown;
        public boolean cancelRefused;
        private final Map<String, UUID> byKey = new HashMap<>();

        @Override
        public UUID open(Draft draft, String idempotencyKey) {
            keys.add(idempotencyKey);
            if (openIsDown) {
                throw ParticipantFailure.technical("sales is down");
            }
            if (refuseOpenWith != null) {
                throw ParticipantFailure.business(refuseOpenWith, "refused");
            }
            UUID existing = byKey.get(idempotencyKey);
            if (existing != null) {
                return existing;
            }
            lastDraft = draft;
            UUID id = UUID.randomUUID();
            orders.put(id, new Snapshot(id, "QUOTATION",
                    List.of(new ReservationRef(draft.reservationId(), draft.productType()))));
            byKey.put(idempotencyKey, id);
            return id;
        }

        @Override
        public Snapshot get(UUID orderId) {
            Snapshot snapshot = orders.get(orderId);
            if (snapshot == null) {
                throw ParticipantFailure.business(FailureReason.ORDER_NOT_FOUND, "no such order");
            }
            return snapshot;
        }

        @Override
        public void cancel(UUID orderId) {
            get(orderId);
            if (cancelRefused) {
                throw ParticipantFailure.business(FailureReason.ORDER_NOT_CANCELLABLE, "in the laboratory");
            }
            cancelled.add(orderId);
        }
    }

    /** Saga store in memory, with the same atomic key rule and lease as the Redis one. */
    public static class Store implements SagaStore {

        private final Map<UUID, SagaInstance> sagas = new LinkedHashMap<>();
        private final Map<String, UUID> byKey = new HashMap<>();
        private final Set<UUID> locked = new HashSet<>();

        @Override
        public Created<SagaInstance> createIfAbsent(String key, SagaInstance saga) {
            UUID existing = byKey.get(key);
            if (existing != null) {
                return new Created<>(sagas.get(existing), false);
            }
            byKey.put(key, saga.id());
            sagas.put(saga.id(), saga);
            return new Created<>(saga, true);
        }

        @Override
        public Optional<SagaInstance> find(UUID id) {
            return Optional.ofNullable(sagas.get(id));
        }

        @Override
        public void save(SagaInstance saga) {
            sagas.put(saga.id(), saga);
        }

        @Override
        public List<UUID> resumable(Instant notChangedSince, int limit) {
            return sagas.values().stream()
                    .filter(s -> !s.status().isFinal() && !s.updatedAt().isAfter(notChangedSince))
                    .map(SagaInstance::id).limit(limit).toList();
        }

        @Override
        public boolean tryLock(UUID id, Duration lease) {
            return locked.add(id);
        }

        @Override
        public void unlock(UUID id) {
            locked.remove(id);
        }

        /** Pretends someone else is executing the saga. */
        public void lockedByAnother(UUID id) {
            locked.add(id);
        }
    }
}
