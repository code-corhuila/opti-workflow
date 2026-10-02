package co.edu.corhuila.opti.workflow.adapter.out.participants;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.opti.workflow.adapter.Correlation;
import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;

/**
 * The one HTTP client the workflow uses to reach the domains: the workflow's own service token
 * (never the person's, it may expire in the middle of an undo), the correlation id of the request,
 * a time limit per call and bounded retries with exponential back-off and full jitter, only for
 * network failures, 429 and 5xx. A 4xx is handed to the caller to classify.
 */
public class ParticipantClient {

    /** Waits; replaced in tests so they do not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** An answer that was not 2xx. */
    public record Refusal(int status, String message) {
    }

    private final HttpClient http;
    private final ObjectMapper json;
    private final String serviceToken;
    private final Duration requestTimeout;
    private final int attempts;
    private final Duration backoffBase;
    private final Sleeper sleeper;
    private final DoubleSupplier random;

    public ParticipantClient(HttpClient http, ObjectMapper json, String serviceToken, Duration requestTimeout,
                             int attempts, Duration backoffBase, Sleeper sleeper, DoubleSupplier random) {
        this.http = http;
        this.json = json;
        this.serviceToken = serviceToken;
        this.requestTimeout = requestTimeout;
        this.attempts = attempts;
        this.backoffBase = backoffBase;
        this.sleeper = sleeper;
        this.random = random;
    }

    /**
     * Calls the participant; {@code classify} turns a 4xx into the failure the saga understands.
     * Anything that cannot be fixed by the caller and is not a 4xx is TECHNICAL.
     */
    public JsonNode call(String method, String url, Object body, String idempotencyKey,
                         Function<Refusal, ParticipantFailure> classify) {
        HttpRequest request = build(method, url, body, idempotencyKey);
        ParticipantFailure last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return parse(response.body());
                }
                if (status == 429 || status >= 500) {
                    last = ParticipantFailure.technical(url + " answered " + status);
                } else if (status == 401 || status == 403) {
                    // a credential problem is an operations problem, not a business refusal
                    throw ParticipantFailure.technical(url + " refused the service credential (" + status + ")");
                } else {
                    throw classify.apply(new Refusal(status, message(response.body())));
                }
            } catch (IOException e) {
                last = ParticipantFailure.technical("no response from " + request.uri().getHost() + ": "
                        + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ParticipantFailure.technical("interrupted while calling " + url);
            }
            if (attempt < attempts) {
                pause(attempt);
            }
        }
        throw last;
    }

    private HttpRequest build(String method, String url, Object body, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("Authorization", "Bearer " + serviceToken)
                .header("Accept", "application/json")
                .header(Correlation.HEADER, Correlation.current());
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        try {
            return builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        } catch (IOException e) {
            throw ParticipantFailure.technical("could not serialize the request to " + url);
        }
    }

    private void pause(int failedAttempt) {
        long ceiling = backoffBase.multipliedBy(1L << failedAttempt).toMillis();
        try {
            sleeper.sleep(Duration.ofMillis((long) (random.getAsDouble() * ceiling)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ParticipantFailure.technical("interrupted while waiting to retry");
        }
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (IOException e) {
            throw ParticipantFailure.technical("a participant answered with a body that is not JSON");
        }
    }

    private String message(String body) {
        try {
            return json.readTree(body).path("message").asText("");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
