package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.corhuila.opti.workflow.adapter.Correlation;

import jakarta.servlet.http.HttpServletResponse;

/** Writes the error envelope from places that run before controllers (servlet filters). */
@Component
public class JsonErrors {

    private final ObjectMapper json;

    public JsonErrors(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(code.name(), message, null, Correlation.current()));
    }
}
