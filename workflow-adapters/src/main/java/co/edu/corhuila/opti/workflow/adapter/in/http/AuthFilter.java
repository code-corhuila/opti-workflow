package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.io.IOException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates every request except the public paths. The token is validated here, in the
 * service: the gateway only checks that a credential is present, and internal callers skip it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);
    private static final String BEARER = "Bearer ";

    private final Rs256Verifier verifier;
    private final Set<String> publicPaths;
    private final JsonErrors errors;

    public AuthFilter(Rs256Verifier verifier, PublicPaths publicPaths, JsonErrors errors) {
        this.verifier = verifier;
        this.publicPaths = publicPaths.paths();
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicPaths.contains(request.getRequestURI())
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            errors.write(response, ErrorCode.UNAUTHORIZED, "a valid bearer token is required");
            return;
        }
        try {
            AuthenticatedUser user = verifier.verify(header.substring(BEARER.length()).trim());
            request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, user);
        } catch (Rs256Verifier.InvalidTokenException e) {
            LOG.warn("token rejected: {}", e.getMessage());
            errors.write(response, ErrorCode.UNAUTHORIZED, "a valid bearer token is required");
            return;
        }
        chain.doFilter(request, response);
    }
}
