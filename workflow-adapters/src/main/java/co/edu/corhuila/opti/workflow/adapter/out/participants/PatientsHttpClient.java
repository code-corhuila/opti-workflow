package co.edu.corhuila.opti.workflow.adapter.out.participants;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import co.edu.corhuila.opti.workflow.application.port.out.ParticipantFailure;
import co.edu.corhuila.opti.workflow.application.port.out.PatientsPort;
import co.edu.corhuila.opti.workflow.domain.saga.FailureReason;

/** The customers domain through its published API. */
public class PatientsHttpClient implements PatientsPort {

    private final ParticipantClient client;
    private final String baseUrl;

    public PatientsHttpClient(ParticipantClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public String requireActive(UUID patientId) {
        JsonNode patient = client.call("GET", baseUrl + "/api/v1/patients/" + patientId, null, null, refusal ->
                refusal.status() == 404
                        ? ParticipantFailure.business(FailureReason.PATIENT_NOT_FOUND, "patient " + patientId + " not found")
                        : ParticipantFailure.business(FailureReason.REJECTED, "customers refused: " + refusal.message()));
        if ("INACTIVE".equals(patient.path("status").asText())) {
            throw ParticipantFailure.business(FailureReason.PATIENT_NOT_ACTIVE, "patient " + patientId + " is inactive");
        }
        return patient.path("fullName").asText("");
    }
}
