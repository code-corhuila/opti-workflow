package co.edu.corhuila.opti.workflow.app;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.opti.workflow.adapter.in.http.PublicPaths;
import co.edu.corhuila.opti.workflow.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.opti.workflow.adapter.out.participants.OrdersHttpClient;
import co.edu.corhuila.opti.workflow.adapter.out.participants.ParticipantClient;
import co.edu.corhuila.opti.workflow.adapter.out.participants.PatientsHttpClient;
import co.edu.corhuila.opti.workflow.adapter.out.participants.StockHttpClient;
import co.edu.corhuila.opti.workflow.adapter.out.state.RedisSagaStore;
import co.edu.corhuila.opti.workflow.application.port.in.SagaUseCases;
import co.edu.corhuila.opti.workflow.application.port.out.IdGenerator;
import co.edu.corhuila.opti.workflow.application.port.out.SagaStore;
import co.edu.corhuila.opti.workflow.application.usecase.SagaService;

/**
 * Composition root: the only place that knows every concrete type. The limits (server timeouts,
 * pool sizes, per-call timeout, attempts, lease) are declared with their value in
 * {@code application.yml}, next to this class.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkflowConfiguration.WorkflowProperties.class)
class WorkflowConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration BACKOFF_BASE = Duration.ofMillis(200);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Rs256Verifier tokenVerifier(ObjectMapper json, Clock clock,
                                @Value("${jwt.public-key:}") String publicKey,
                                @Value("${jwt.public-key-file:}") String publicKeyFile) throws IOException {
        String pem = publicKey.isBlank() && !publicKeyFile.isBlank()
                ? Files.readString(Path.of(publicKeyFile)) : publicKey;
        if (pem.isBlank()) {
            throw new IllegalStateException("Set JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE (identity service public key)");
        }
        return new Rs256Verifier(pem, json, clock);
    }

    @Bean
    PublicPaths publicPaths() {
        return PublicPaths.with();
    }

    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }

    @Bean
    SagaStore sagaStore(StringRedisTemplate redis, ObjectMapper json) {
        return new RedisSagaStore(redis, json);
    }

    @Bean
    ParticipantClient participantClient(ObjectMapper json, WorkflowProperties properties) {
        requireUrls(properties);
        return new ParticipantClient(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), json,
                properties.serviceToken(), properties.httpTimeout(), properties.httpAttempts(), BACKOFF_BASE,
                d -> Thread.sleep(d.toMillis()), () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    SagaUseCases sagaUseCases(ParticipantClient client, WorkflowProperties properties, SagaStore store,
                              IdGenerator ids, Clock clock) {
        return new SagaService(new PatientsHttpClient(client, properties.customersUrl()),
                new StockHttpClient(client, properties.productsUrl()),
                new OrdersHttpClient(client, properties.salesUrl()), store, ids, clock,
                properties.sagaMaxAttempts(), properties.sagaLease(), properties.sagaResumeAfter());
    }

    private static void requireUrls(WorkflowProperties p) {
        if (p.serviceToken().isBlank() || p.customersUrl().isBlank() || p.productsUrl().isBlank()
                || p.salesUrl().isBlank()) {
            throw new IllegalStateException(
                    "Set SERVICE_TOKEN, CUSTOMERS_API_URL, PRODUCTS_API_URL and SALES_API_URL");
        }
    }

    /** Settings of the workflow (see the {@code workflow:} block of application.yml). */
    @ConfigurationProperties("workflow")
    record WorkflowProperties(String serviceToken, String customersUrl, String productsUrl, String salesUrl,
                              Duration httpTimeout, int httpAttempts, int sagaMaxAttempts, Duration sagaLease,
                              Duration sagaResumeAfter, Duration sagaResumeEvery) {
    }

    /** Resumes the sagas that stopped halfway: a participant was down, or the workflow itself restarted. */
    @Component
    static class SagaRecovery {

        private static final Logger LOG = LoggerFactory.getLogger(SagaRecovery.class);

        private final SagaUseCases sagas;

        SagaRecovery(SagaUseCases sagas) {
            this.sagas = sagas;
        }

        @Scheduled(fixedDelayString = "${workflow.saga-resume-every:PT30S}", initialDelayString = "PT10S")
        void resume() {
            try {
                int resumed = sagas.resumeStuck();
                if (resumed > 0) {
                    LOG.info("resumed {} saga(s) that had stopped", resumed);
                }
            } catch (RuntimeException e) {
                LOG.error("resuming stuck sagas failed; the next round starts clean", e);
            }
        }
    }
}
