package co.edu.corhuila.opti.workflow.adapter.out.state;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.opti.workflow.application.port.out.Created;
import co.edu.corhuila.opti.workflow.application.port.out.SagaStore;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.opti.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.opti.workflow.domain.saga.SagaType;

/**
 * Saga state in Redis (a team-registered choice, numeral 5.8.4). Each saga is one JSON value; the idempotency key of the start and
 * the saga are written by a single Lua script, so two requests with the same key can never create
 * two sagas. Sagas that are not final live in a sorted set scored by their last change, which is
 * how the resume finds the ones that stopped. Redis runs with append-only persistence.
 */
public class RedisSagaStore implements SagaStore {

    private static final String SAGA = "saga:";
    private static final String KEY = "saga-key:";
    private static final String LOCK = "saga-lock:";
    private static final String ACTIVE = "sagas:active";
    private static final Duration RETENTION = Duration.ofDays(30);

    private static final DefaultRedisScript<Long> CREATE_IF_ABSENT = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[5]) then
                redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[5])
                redis.call('ZADD', KEYS[3], ARGV[3], ARGV[4])
                return 1
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_IF_MINE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Map<UUID, String> myLocks = new ConcurrentHashMap<>();

    public RedisSagaStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public Created<SagaInstance> createIfAbsent(String idempotencyKey, SagaInstance saga) {
        Long created = redis.execute(CREATE_IF_ABSENT, List.of(KEY + idempotencyKey, SAGA + saga.id(), ACTIVE),
                saga.id().toString(), write(saga), String.valueOf(saga.updatedAt().toEpochMilli()),
                saga.id().toString(), String.valueOf(RETENTION.toSeconds()));
        if (Long.valueOf(1).equals(created)) {
            return new Created<>(saga, true);
        }
        UUID existing = UUID.fromString(redis.opsForValue().get(KEY + idempotencyKey));
        return new Created<>(find(existing).orElseThrow(), false);
    }

    @Override
    public Optional<SagaInstance> find(UUID id) {
        String value = redis.opsForValue().get(SAGA + id);
        return value == null ? Optional.empty() : Optional.of(read(value));
    }

    @Override
    public void save(SagaInstance saga) {
        redis.opsForValue().set(SAGA + saga.id(), write(saga), RETENTION);
        if (saga.status().isFinal()) {
            redis.opsForZSet().remove(ACTIVE, saga.id().toString());
        } else {
            redis.opsForZSet().add(ACTIVE, saga.id().toString(), saga.updatedAt().toEpochMilli());
        }
    }

    @Override
    public List<UUID> resumable(Instant notChangedSince, int limit) {
        Set<String> ids = redis.opsForZSet().rangeByScore(ACTIVE, 0, notChangedSince.toEpochMilli(), 0, limit);
        return ids == null ? List.of() : ids.stream().map(UUID::fromString).toList();
    }

    @Override
    public boolean tryLock(UUID id, Duration lease) {
        String token = UUID.randomUUID().toString();
        boolean taken = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK + id, token, lease));
        if (taken) {
            myLocks.put(id, token);
        }
        return taken;
    }

    @Override
    public void unlock(UUID id) {
        String token = myLocks.remove(id);
        if (token != null) {
            redis.execute(UNLOCK_IF_MINE, List.of(LOCK + id), token);
        }
    }

    private String write(SagaInstance saga) {
        try {
            return json.writeValueAsString(SagaRecord.from(saga));
        } catch (IOException e) {
            throw new IllegalStateException("could not serialize saga " + saga.id(), e);
        }
    }

    private SagaInstance read(String value) {
        try {
            return json.readValue(value, SagaRecord.class).toSaga();
        } catch (IOException e) {
            throw new IllegalStateException("could not read a saga from the state store", e);
        }
    }

    /** The stored shape: plain strings and numbers, independent of the domain class. */
    record SagaRecord(String id, String type, String status, Map<String, String> data, List<String> completedSteps,
                      List<String> compensatedSteps, String failedStep, String failureReason, String detail,
                      int attempts, String createdAt, String updatedAt) {

        static SagaRecord from(SagaInstance s) {
            return new SagaRecord(s.id().toString(), s.type().name(), s.status().name(), s.data(),
                    s.completedSteps(), s.compensatedSteps(), s.failedStep(),
                    s.failureReason() == null ? null : s.failureReason().name(), s.detail(), s.attempts(),
                    s.createdAt().toString(), s.updatedAt().toString());
        }

        SagaInstance toSaga() {
            return SagaInstance.rehydrate(UUID.fromString(id), SagaType.valueOf(type), SagaStatus.valueOf(status),
                    data, completedSteps, compensatedSteps, failedStep,
                    failureReason == null ? null : FailureReason.valueOf(failureReason), detail, attempts,
                    Instant.parse(createdAt), Instant.parse(updatedAt));
        }
    }
}
