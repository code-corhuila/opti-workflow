package co.edu.corhuila.opti.workflow.application.port.out;

import java.util.UUID;

/** The customers domain as the workflow sees it. */
public interface PatientsPort {

    /** Returns the patient's full name; refuses (BUSINESS) when the patient does not exist or is not active. */
    String requireActive(UUID patientId);
}
