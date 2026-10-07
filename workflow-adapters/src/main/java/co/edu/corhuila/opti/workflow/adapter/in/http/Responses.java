package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;

import co.edu.corhuila.opti.workflow.application.port.out.Created;

/** Builders for the creation responses every service shares. */
public final class Responses {

    private Responses() {
    }

    /** Body of a creation: only the id, the client reads the resource from {@code Location}. */
    public record CreatedBody(UUID id) {
    }

    /** {@code 201} with {@code Location} the first time, {@code 200} with the same id when repeated. */
    public static ResponseEntity<CreatedBody> created(Created<?> result, UUID id, String collectionPath) {
        CreatedBody body = new CreatedBody(id);
        if (!result.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create(collectionPath + "/" + id)).body(body);
    }
}
