package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validates a JWT by itself: only {@code RS256} is accepted (never {@code none} or {@code HS256}),
 * the signature is checked with the identity service's public key, {@code exp} and {@code sub}
 * are mandatory. This service holds no private key and no shared secret.
 */
public class Rs256Verifier {

    private static final long CLOCK_SKEW_SECONDS = 30;

    private final PublicKey publicKey;
    private final ObjectMapper json;
    private final Clock clock;

    public Rs256Verifier(String publicKeyPem, ObjectMapper json, Clock clock) {
        this.publicKey = parse(publicKeyPem);
        this.json = json;
        this.clock = clock;
    }

    /** Returns the verified identity or throws {@link InvalidTokenException}. */
    public AuthenticatedUser verify(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("malformed token");
        }
        try {
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())) {
                throw new InvalidTokenException("unsupported algorithm");
            }
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new InvalidTokenException("bad signature");
            }
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return toUser(claims);
        } catch (InvalidTokenException e) {
            throw e;
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException e) {
            throw new InvalidTokenException("unreadable token");
        }
    }

    private AuthenticatedUser toUser(JsonNode claims) {
        if (!claims.hasNonNull("exp") || !claims.path("exp").canConvertToLong()) {
            throw new InvalidTokenException("missing exp");
        }
        long now = clock.instant().getEpochSecond();
        if (claims.path("exp").asLong() + CLOCK_SKEW_SECONDS < now) {
            throw new InvalidTokenException("expired token");
        }
        String subject = claims.path("sub").asText("");
        if (subject.isBlank()) {
            throw new InvalidTokenException("missing sub");
        }
        List<String> roles = new java.util.ArrayList<>();
        claims.path("roles").forEach(role -> roles.add(role.asText()));
        return new AuthenticatedUser(subject, Set.copyOf(roles));
    }

    private static PublicKey parse(String pem) {
        try {
            String body = pem.replace("\\n", "\n")
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("JWT public key is not a valid RSA public key (PEM)", e);
        }
    }

    /** Raised for any token that must be answered with 401. */
    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) {
            super(message);
        }
    }
}
