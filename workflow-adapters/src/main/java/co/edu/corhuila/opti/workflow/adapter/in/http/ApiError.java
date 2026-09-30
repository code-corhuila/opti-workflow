package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The single error envelope used by every response, including unknown routes and bad JSON. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, List<Detail> details, String traceId) {

    /** One invalid field (or header) and why. */
    public record Detail(String field, String message) {
    }
}
