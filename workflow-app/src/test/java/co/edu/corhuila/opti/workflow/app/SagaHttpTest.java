package co.edu.corhuila.opti.workflow.app;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import co.edu.corhuila.opti.workflow.testsupport.Participants;
import co.edu.corhuila.opti.workflow.testsupport.TestClock;

/**
 * Contract checks of the workflow (Annex E): the same authentication, envelope and validation as
 * any -api, an idempotent start, and a response that never carries the internal detail of a failure.
 */
@SpringBootTest(classes = HttpTestApplication.class)
@AutoConfigureMockMvc
class SagaHttpTest {

    private static final String PLACE = "/api/v1/sagas/place-order";
    private static final String CANCEL = "/api/v1/sagas/cancel-order";

    @Autowired
    MockMvc mvc;
    @Autowired
    TestClock clock;
    @Autowired
    Participants.Patients patients;
    @Autowired
    Participants.Stock stock;
    @Autowired
    Participants.Orders orders;

    @BeforeEach
    void reset() {
        patients.down = false;
        patients.active.add(HttpTestApplication.PATIENT);
        orders.refuseOpenWith = null;
        orders.openIsDown = false;
        stock.failRelease = false;
    }

    // ---- authentication and envelope ------------------------------------------------------

    @Test
    void healthNeedsNoTokenAndEverythingElseDoes() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(post(PLACE).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").exists());
        mvc.perform(get("/api/v1/sagas/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void brokenTokensAreRejected() throws Exception {
        var now = clock.instant();
        for (String token : java.util.List.of(TestTokens.algNone(now), TestTokens.hs256WithPublicKey(now),
                TestTokens.expired(now), TestTokens.signedByOtherKey(now), TestTokens.withoutSubject(now))) {
            mvc.perform(post(PLACE).header("Authorization", "Bearer " + token).header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(body()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void roleAndCorrelationAreEnforced() throws Exception {
        as(post(PLACE).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body()),
                "OPTOMETRIST").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("FORBIDDEN"));
        as(post(PLACE).header("Idempotency-Key", key()).header("X-Correlation-Id", "e2e-saga-1")
                .contentType(MediaType.APPLICATION_JSON).content("{ nope"), "SELLER")
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Correlation-Id", "e2e-saga-1"))
                .andExpect(jsonPath("$.traceId").value("e2e-saga-1"));
        as(get("/api/v1/nothing"), "SELLER").andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        as(get("/api/v1/sagas/not-a-uuid"), "SELLER").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("id"));
        as(get("/api/v1/sagas/" + UUID.randomUUID()), "SELLER").andExpect(status().isNotFound());
    }

    // ---- validation -----------------------------------------------------------------------

    @Test
    void invalidBodyNamesEveryFieldIncludingTheIdempotencyKey() throws Exception {
        as(post(PLACE).contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":11}"), "SELLER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[*].field", hasItems("Idempotency-Key", "patientId", "productType", "productId", "quantity")));
        as(post(PLACE).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"x\",\"productId\":\"y\",\"quantity\":1}"), "SELLER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", hasItem("patientId")));
        as(post(PLACE).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(body().replaceFirst("\\{", "{\"colour\":1,")), "SELLER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", hasItem("colour")));
        as(post(CANCEL).contentType(MediaType.APPLICATION_JSON).content("{}"), "SELLER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[*].field", hasItems("Idempotency-Key", "orderId")));
    }

    // ---- idempotent start -----------------------------------------------------------------

    @Test
    void firstStartIs201WithLocationAndTheRetryIs200WithTheSameSaga() throws Exception {
        String key = key();

        String id = idOf(as(place(key), "SELLER")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/sagas/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.type").value("PLACE_ORDER"))
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.completedSteps[0]").value("check-patient"))
                .andReturn().getResponse().getContentAsString());

        int reservationsBefore = stock.reservations.size();
        as(place(key), "SELLER").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertEqual(reservationsBefore, stock.reservations.size());

        as(get("/api/v1/sagas/" + id), "SELLER").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // ---- failures and what the response reveals -------------------------------------------

    @Test
    void aRefusedSagaAnswersWithTheFailedStepAndASafeReasonAndNeverTheInternalDetail() throws Exception {
        orders.refuseOpenWith = co.edu.corhuila.opti.workflow.domain.saga.FailureReason.REJECTED;

        as(place(key()), "SELLER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPENSATED"))
                .andExpect(jsonPath("$.failedStep").value("open-order"))
                .andExpect(jsonPath("$.failureReason").value("REJECTED"))
                .andExpect(content().string(not(containsString("refused"))))
                .andExpect(content().string(not(containsString("detail"))));
    }

    @Test
    void anUnknownPatientIsCompensatedWithItsReason() throws Exception {
        patients.active.clear();

        as(place(key()), "SELLER")
                .andExpect(jsonPath("$.status").value("COMPENSATED"))
                .andExpect(jsonPath("$.failedStep").value("check-patient"))
                .andExpect(jsonPath("$.failureReason").value("PATIENT_NOT_FOUND"));
    }

    @Test
    void aParticipantThatIsDownLeavesTheSagaRunningForTheResume() throws Exception {
        patients.down = true;

        as(place(key()), "SELLER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.failedStep").doesNotExist());
    }

    @Test
    void cancellingAnOrderIsIdempotentToo() throws Exception {
        String saga = as(place(key()), "SELLER").andReturn().getResponse().getContentAsString();
        String orderId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(saga).path("orderId").asText();
        String key = key();

        as(cancel(orderId, key), "SELLER").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.type").value("CANCEL_ORDER"));
        as(cancel(orderId, key), "SELLER").andExpect(status().isOk());
        as(cancel(UUID.randomUUID().toString(), key()), "SERVICE")
                .andExpect(jsonPath("$.status").value("COMPENSATED"))
                .andExpect(jsonPath("$.failureReason").value("ORDER_NOT_FOUND"));
    }

    // ---- helpers --------------------------------------------------------------------------

    private ResultActions as(MockHttpServletRequestBuilder request, String... roles) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + TestTokens.valid(clock.instant(), roles)));
    }

    private MockHttpServletRequestBuilder place(String key) {
        return post(PLACE).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body());
    }

    private MockHttpServletRequestBuilder cancel(String orderId, String key) {
        return post(CANCEL).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\"}");
    }

    private static String body() {
        return "{\"patientId\":\"" + HttpTestApplication.PATIENT + "\",\"productType\":\"FRAME\",\"productId\":\""
                + HttpTestApplication.FRAME + "\",\"quantity\":1}";
    }

    private static String key() {
        return "saga-" + UUID.randomUUID();
    }

    private static String idOf(String responseBody) {
        int start = responseBody.indexOf("\"id\":\"") + 6;
        return responseBody.substring(start, responseBody.indexOf('"', start));
    }

    private static void assertEqual(int expected, int actual) {
        org.assertj.core.api.Assertions.assertThat(actual).isEqualTo(expected);
    }
}
