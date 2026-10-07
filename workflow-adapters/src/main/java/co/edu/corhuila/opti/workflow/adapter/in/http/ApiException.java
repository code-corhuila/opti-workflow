package co.edu.corhuila.opti.workflow.adapter.in.http;

import java.util.List;

/** Error raised by the HTTP adapter itself (bad input shape, missing permission). */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<ApiError.Detail> details;

    public ApiException(ErrorCode code, String message, List<ApiError.Detail> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "the request has invalid fields",
                List.of(new ApiError.Detail(field, message)));
    }

    public static ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "you do not have permission for this operation", null);
    }

    public ErrorCode code() {
        return code;
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
