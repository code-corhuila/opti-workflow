package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import co.edu.corhuila.opti.workflow.application.port.in.PageQuery;

import jakarta.servlet.http.HttpServletRequest;

/** Shape rules every endpoint shares: ids, idempotency key, pagination, allowed filters, roles. */
public final class RequestRules {

    private RequestRules() {
    }

    public static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(field, "must be a UUID");
        }
    }

    /** Parses {@code page} and {@code limit}; reports every invalid one, not just the first. */
    public static PageQuery page(String page, String limit) {
        List<ApiError.Detail> problems = new ArrayList<>();
        int pageNumber = intOrDefault(page, 1, "page", 1, Integer.MAX_VALUE, problems);
        int pageSize = intOrDefault(limit, PageQuery.DEFAULT_LIMIT, "limit", 1, PageQuery.MAX_LIMIT, problems);
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "the request has invalid fields", problems);
        }
        return new PageQuery(pageNumber, pageSize);
    }

    /** Rejects any query parameter the endpoint does not declare. */
    public static void onlyParams(HttpServletRequest request, String... allowed) {
        Set<String> known = Set.of(allowed);
        List<ApiError.Detail> unknown = request.getParameterMap().keySet().stream()
                .filter(name -> !known.contains(name))
                .map(name -> new ApiError.Detail(name, "unknown filter"))
                .toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "the request has invalid fields", unknown);
        }
    }

    /** Requires one of the roles; a service-to-service caller carries the {@code SERVICE} role. */
    public static AuthenticatedUser requireRole(HttpServletRequest request, String... roles) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE);
        if (user == null || !user.hasAnyRole(roles)) {
            throw ApiException.forbidden();
        }
        return user;
    }

    public static AuthenticatedUser caller(HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE);
    }

    private static int intOrDefault(String raw, int fallback, String field, int min, int max,
                                    List<ApiError.Detail> problems) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < min || value > max) {
                problems.add(new ApiError.Detail(field, max == Integer.MAX_VALUE
                        ? "must be " + min + " or greater" : "must be between " + min + " and " + max));
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            problems.add(new ApiError.Detail(field, "must be an integer"));
            return fallback;
        }
    }
}
