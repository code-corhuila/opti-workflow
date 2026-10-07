package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.util.Set;

/** Paths served without a token. {@code /health} is always public; a service may add its own. */
public record PublicPaths(Set<String> paths) {

    public static PublicPaths with(String... extra) {
        java.util.Set<String> all = new java.util.HashSet<>(java.util.List.of(extra));
        all.add("/health");
        return new PublicPaths(Set.copyOf(all));
    }
}
