package co.edu.corhuila.opti.workflow.app;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Builds JWTs for the HTTP tests, valid and deliberately broken. */
final class TestTokens {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final KeyPair IDENTITY = newKeyPair();
    private static final KeyPair STRANGER = newKeyPair();

    private TestTokens() {
    }

    /** Public key of the "identity service", in the PEM form the service reads from its environment. */
    static String publicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(IDENTITY.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----";
    }

    /** Private key of the "identity service": only the auth service under test may use it, to sign. */
    static String privateKeyPem() {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(IDENTITY.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
    }

    static String valid(Instant now, String... roles) {
        return rs256(IDENTITY.getPrivate(), now, "44444444-4444-4444-8444-444444444444", roles);
    }

    static String expired(Instant now) {
        return sign("RS256", claims("44444444-4444-4444-8444-444444444444", now.minusSeconds(7200), now.minusSeconds(3600)), IDENTITY.getPrivate());
    }

    static String signedByOtherKey(Instant now) {
        return rs256(STRANGER.getPrivate(), now, "44444444-4444-4444-8444-444444444444", "ADMIN");
    }

    static String withoutSubject(Instant now) {
        return sign("RS256", claims(null, now, now.plusSeconds(3600), "ADMIN"), IDENTITY.getPrivate());
    }

    static String algNone(Instant now) {
        return B64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(claims("44444444-4444-4444-8444-444444444444", now, now.plusSeconds(3600), "ADMIN").getBytes(StandardCharsets.UTF_8))
                + ".";
    }

    /** The classic forgery: HS256 using the public key as the HMAC secret. */
    static String hs256WithPublicKey(Instant now) {
        try {
            String head = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String body = B64.encodeToString(claims("44444444-4444-4444-8444-444444444444", now, now.plusSeconds(3600), "ADMIN")
                    .getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(publicKeyPem().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return head + "." + body + "." + B64.encodeToString(
                    mac.doFinal((head + "." + body).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rs256(PrivateKey key, Instant now, String subject, String... roles) {
        return sign("RS256", claims(subject, now, now.plusSeconds(3600), roles), key);
    }

    private static String claims(String subject, Instant issued, Instant expires, String... roles) {
        String sub = subject == null ? "" : "\"sub\":\"" + subject + "\",";
        String roleList = List.of(roles).stream().map(r -> "\"" + r + "\"").collect(Collectors.joining(","));
        return "{" + sub + "\"roles\":[" + roleList + "],\"iat\":" + issued.getEpochSecond()
                + ",\"exp\":" + expires.getEpochSecond() + "}";
    }

    private static String sign(String alg, String claims, PrivateKey key) {
        try {
            String head = B64.encodeToString(("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}").getBytes(StandardCharsets.UTF_8));
            String body = B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update((head + "." + body).getBytes(StandardCharsets.US_ASCII));
            return head + "." + body + "." + B64.encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
