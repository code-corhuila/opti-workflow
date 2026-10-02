package co.edu.corhuila.opti.workflow.app;

import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.opti.workflow.adapter.in.http.PublicPaths;
import co.edu.corhuila.opti.workflow.adapter.in.http.Rs256Verifier;
import co.edu.corhuila.opti.workflow.application.port.in.SagaUseCases;
import co.edu.corhuila.opti.workflow.application.usecase.SagaService;
import co.edu.corhuila.opti.workflow.testsupport.Participants;
import co.edu.corhuila.opti.workflow.testsupport.TestClock;

/**
 * Boots only the HTTP adapter over fake participants and an in-memory store: same filters, same
 * error handling, same controller as production, no Redis and no other service.
 */
@SpringBootApplication(scanBasePackages = "co.edu.corhuila.opti.workflow.adapter.in.http",
        exclude = {RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class})
class HttpTestApplication {

    static final UUID PATIENT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID FRAME = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Bean
    TestClock clock() {
        return TestClock.at("2026-09-29T15:00:00Z");
    }

    @Bean
    Rs256Verifier verifier(ObjectMapper json, TestClock clock) {
        return new Rs256Verifier(TestTokens.publicKeyPem(), json, clock);
    }

    @Bean
    PublicPaths publicPaths() {
        return PublicPaths.with();
    }

    @Bean
    Participants.Patients patients() {
        var patients = new Participants.Patients();
        patients.active.add(PATIENT);
        return patients;
    }

    @Bean
    Participants.Stock stock() {
        var stock = new Participants.Stock();
        stock.stock.put(FRAME, 1000);
        return stock;
    }

    @Bean
    Participants.Orders orders() {
        return new Participants.Orders();
    }

    @Bean
    SagaUseCases sagaUseCases(Participants.Patients patients, Participants.Stock stock, Participants.Orders orders,
                              TestClock clock) {
        return new SagaService(patients, stock, orders, new Participants.Store(), UUID::randomUUID, clock, 3,
                Duration.ofSeconds(30), Duration.ofSeconds(30));
    }
}
