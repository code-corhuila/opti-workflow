package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.util.Set;

/** Identity taken from a verified token: who (sub) and with which roles. */
public record AuthenticatedUser(String subject, Set<String> roles) {

    public static final String REQUEST_ATTRIBUTE = AuthenticatedUser.class.getName();

    public boolean hasAnyRole(String... allowed) {
        for (String role : allowed) {
            if (roles.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
