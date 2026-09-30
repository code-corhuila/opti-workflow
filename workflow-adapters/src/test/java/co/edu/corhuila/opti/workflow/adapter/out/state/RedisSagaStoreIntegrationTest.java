package co.edu.corhuila.opti.workflow.adapter.out.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.opti.workflow.application.port.out.Created;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;
import co.edu.corhuila.opti.workflow.domain.saga.SagaInstance;
import co.edu.corhuila.opti.workflow.domain.saga.SagaStatus;
import co.edu.corhuila.opti.workflow.domain.saga.SagaType;

/**
 * Runs the store against a real Redis. {@code TEST_REDIS_PORT} (and optionally {@code TEST_REDIS_HOST},
 * default localhost) enables it; without it the test is skipped, not failed.
 */
@EnabledIfEnvironmentVariable(named = "TEST_REDIS_PORT", matches = "\\d+")
class RedisSagaStoreIntegrationTest {

    private static LettuceConnectionFactory factory;
    private static RedisSagaStore store;

    @BeforeAll
    static void connect() {
        var config = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("TEST_REDIS_HOST", "localhost"), Integer.parseInt(System.getenv("TEST_REDIS_PORT")));
        factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        var template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        store = new RedisSagaStore(template, new ObjectMapper());
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    private static SagaInstance newSaga() {
        return SagaInstance.start(UUID.randomUUID(), SagaType.PLACE_ORDER, Map.of("patientId", "p", "quantity", "1"),
                Instant.now());
    }

    @Test
    void savesAndReadsBackEveryFieldOfASaga() {
        SagaInstance saga = newSaga();
        store.createIfAbsent("it-" + UUID.randomUUID(), saga);
        SagaInstance progressed = saga.stepDone("check-patient", Map.of("patientName", "Laura Ortega"), Instant.now())
                .businessFailure("reserve-stock", FailureReason.INSUFFICIENT_STOCK, "only 0 left", Instant.now())
                .stepCompensated("check-patient", Instant.now());

        store.save(progressed);
        SagaInstance read = store.find(saga.id()).orElseThrow();

        assertThat(read.status()).isEqualTo(SagaStatus.COMPENSATING);
        assertThat(read.completedSteps()).containsExactly("check-patient");
        assertThat(read.compensatedSteps()).containsExactly("check-patient");
        assertThat(read.failedStep()).isEqualTo("reserve-stock");
        assertThat(read.failureReason()).isEqualTo(FailureReason.INSUFFICIENT_STOCK);
        assertThat(read.detail()).isEqualTo("only 0 left");
        assertThat(read.datum("patientName")).isEqualTo("Laura Ortega");
        assertThat(read.createdAt()).isEqualTo(saga.createdAt());
    }

    @Test
    void theSameKeyNeverCreatesTwoSagasEvenUnderConcurrency() throws Exception {
        String key = "it-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Created<SagaInstance>>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                SagaInstance candidate = newSaga();
                attempts.add(pool.submit(() -> {
                    start.await();
                    return store.createIfAbsent(key, candidate);
                }));
            }
            start.countDown();
            long created = 0;
            var ids = new java.util.HashSet<UUID>();
            for (var attempt : attempts) {
                var result = attempt.get(20, TimeUnit.SECONDS);
                created += result.created() ? 1 : 0;
                ids.add(result.value().id());
            }

            assertThat(created).isEqualTo(1);
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyNonFinalSagasThatStoppedAreResumable() {
        SagaInstance old = SagaInstance.start(UUID.randomUUID(), SagaType.CANCEL_ORDER, Map.of("orderId", "o"),
                Instant.now().minusSeconds(120));
        store.createIfAbsent("it-" + UUID.randomUUID(), old);
        SagaInstance recent = newSaga();
        store.createIfAbsent("it-" + UUID.randomUUID(), recent);
        SagaInstance finished = SagaInstance.start(UUID.randomUUID(), SagaType.CANCEL_ORDER, Map.of("orderId", "o"),
                Instant.now().minusSeconds(120));
        store.createIfAbsent("it-" + UUID.randomUUID(), finished);
        store.save(finished.complete(Instant.now().minusSeconds(100)));

        List<UUID> resumable = store.resumable(Instant.now().minusSeconds(30), 50);

        assertThat(resumable).contains(old.id()).doesNotContain(recent.id(), finished.id());
    }

    @Test
    void theLeaseIsExclusiveAndOnlyTheHolderReleasesIt() throws Exception {
        UUID id = UUID.randomUUID();

        assertThat(store.tryLock(id, Duration.ofSeconds(5))).isTrue();
        assertThat(store.tryLock(id, Duration.ofSeconds(5))).as("held").isFalse();
        store.unlock(id);
        assertThat(store.tryLock(id, Duration.ofMillis(300))).as("released").isTrue();
        Thread.sleep(500);
        assertThat(store.tryLock(id, Duration.ofSeconds(5))).as("the lease expired by itself").isTrue();
        store.unlock(id);
    }
}
