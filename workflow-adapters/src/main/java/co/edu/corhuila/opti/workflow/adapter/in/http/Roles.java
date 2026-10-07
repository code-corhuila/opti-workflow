package co.edu.corhuila.opti.workflow.adapter.in.http;

/** Role names carried in the {@code roles} claim of the token issued by the identity service. */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String SELLER = "SELLER";
    public static final String OPTOMETRIST = "OPTOMETRIST";
    /** Service-to-service identity (worker, workflow). */
    public static final String SERVICE = "SERVICE";

    private Roles() {
    }
}
